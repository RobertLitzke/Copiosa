#!/usr/bin/env python3
"""Build an offline dictionary pack (SQLite, gzipped) for Copiosa from Wiktextract dumps.

The app (com.copiosa.data.translate.LocalDictionary) downloads one
``dict_<lang>.sqlite.gz`` per language and queries it entirely offline. This script turns
kaikki.org / Wiktextract JSONL extracts into that pack, keeping what the app needs:

  * target-language definitions (glosses), with their register/dialect labels and one example
    sentence per sense,
  * English definitions of the same headwords, and
  * an inflected-form -> lemma index (conjugations, plurals, ...), so selecting "vivimos"
    resolves to "vivir", and
  * each noun's grammatical gender, so a saved noun gets its article without an LLM call.

Two source extracts, because no single one carries both sides:

  --input     the *target-language edition*, e.g. the "Espanol" JSONL from
              https://kaikki.org/eswiktionary/ . Supplies the monolingual glosses, plus the
              register/dialect labels and example sentences hanging off each sense.
  --en-input  the *English edition* for the same language, e.g.
              https://kaikki.org/dictionary/Spanish/ . Supplies the English glosses, and the
              richest inflection tables (its forms are merged either way).

Sense numbering is not comparable across the two editions -- each splits meanings its own way --
so English glosses live in their own table, matched to a headword by (key, part of speech)
rather than by pretending sense 3 of one is sense 3 of the other.

Usage:
    python build_dictionary.py --input data/eswiktionary-raw.jsonl.gz --lang es         --en-input data/enwiktionary-Spanish.jsonl.gz --output dist/

Produces:  dist/dict_es.sqlite  and  dist/dict_es.sqlite.gz   (upload the .gz to GitHub Releases)

Schema 4 (kept in sync with LocalDictionary.kt):
    lemmas(id INTEGER PRIMARY KEY, key TEXT, lemma TEXT, pos TEXT, gender TEXT)  -- one row per
        headword+pos; gender is M/F/MF/MPL/FPL on a noun, else NULL
    senses(lemma_id INTEGER, seq INTEGER, gloss TEXT, labels TEXT, example TEXT)  -- capped
    en_senses(lemma_id INTEGER, seq INTEGER, gloss TEXT)           -- English, capped
    forms(key TEXT, lemma_id INTEGER, tags TEXT)                   -- inflected form -> lemma
    meta(key TEXT PRIMARY KEY, value TEXT)
"""

from __future__ import annotations

import argparse
import datetime as _dt
import gzip
import io
import json
import os
import shutil
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cardtool.wiktionary import gender_of  # noqa: E402

# Mirror LocalDictionary.normalize() so stored keys match the app's lookups.
_STRIP = "¿?¡!.,;:\"'«»()—–"

# Keep the first few senses per headword; the long tail of rare senses is what bloats the pack.
MAX_SENSES = 6

# Longer than this and an "example" is a block quote, not a sentence that fits on a card.
MAX_EXAMPLE_CHARS = 160


def normalize(word: str) -> str:
    return word.strip().strip(_STRIP).lower()


def open_jsonl(path: str) -> io.TextIOBase:
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8")
    return open(path, "r", encoding="utf-8")


def iter_entries(path: str, lang: str):
    """Yields Wiktextract word objects for the target language only."""
    with open_jsonl(path) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj.get("lang_code") != lang:
                continue
            if not obj.get("word"):
                continue
            yield obj


def join_tags(tags) -> str:
    return " ".join(t for t in (tags or []) if isinstance(t, str)).strip()


def verb_mismatch(a: str | None, b: str | None) -> bool:
    """True when exactly one of the two parts of speech is a verb.

    The editions disagree about parts of speech often enough to be worth bridging -- one calls
    "rojo" a noun where the other calls it an adjective, and either way the meanings are the same
    word. Verbs are the exception: English glosses read as "to run, to flow" and are plainly wrong
    against a noun's senses, so a crossing there is dropped rather than guessed at.
    """
    return (a == "verb") != (b == "verb")


def is_form_of(sense) -> bool:
    """True for a sense that only says "plural of X" / "third-person singular of Y"."""
    if sense.get("form_of") or sense.get("alt_of"):
        return True
    return "form-of" in (sense.get("tags") or [])


# Sense tags that describe grammar rather than usage. The part of speech and the headword are
# already on screen, so repeating "noun" or "feminine" beside a definition says nothing; what a
# learner needs from this field is where and how the sense is used. Verb-frame tags (pronominal,
# transitive, ...) are deliberately *not* here -- those do change how the word is used in a sentence.
GRAMMATICAL_TAGS = frozenset(
    """
    form-of no-gloss abbreviation acronym initialism
    noun verb adjective adverb pronoun preposition conjunction interjection numeral article
    determiner participle particle proper-noun prefix suffix letter symbol
    masculine feminine neuter common-gender ambiguous singular plural dual invariable countable
    uncountable
    """.split()
)


def sense_labels(sense) -> str:
    """Register/dialect/domain markers on a sense: "colloquial Argentina", "nautical", "vulgar".

    Wiktextract splits these across ``tags`` (colloquial, outdated, Mexico, derogatory) and
    ``topics`` (nautical, anatomy, botany); a learner reads them as one label, so they are stored
    as one. Deduplicated with order preserved, since the two lists overlap on some entries.

    An inflection entry gets none: "form-of" is not a register, and with hundreds of thousands of
    conjugated forms in the pack it would otherwise be by far the most common label in it.
    """
    if is_form_of(sense):
        return ""
    seen = {}
    for t in (sense.get("tags") or []) + (sense.get("topics") or []):
        if isinstance(t, str) and t.strip() and t.strip() not in GRAMMATICAL_TAGS:
            seen.setdefault(t.strip(), None)
    return " ".join(seen)


def gender_tags(obj, sense) -> set[str]:
    """The gender evidence one noun sense carries.

    The two editions file it in different places: the Spanish edition tags the whole entry
    (``tags: ["feminine"]``), the English edition tags each sense -- which is what lets *mano* count
    ten feminine senses against one masculine slang one. Reading the union covers both.
    """
    tags = set(obj.get("tags") or []) | set(sense.get("tags") or [])
    return {t for t in tags if isinstance(t, str)}


def sense_example(sense) -> str | None:
    """The first usable example sentence on a sense, or None.

    Examples carry a ``text`` plus sometimes a literary ``ref``; only the sentence is kept -- the
    citation is dead weight on a card that has one line to spare. Very long ones are dropped rather
    than truncated, because a half-sentence teaches nothing.
    """
    for ex in (sense.get("examples") or []):
        if not isinstance(ex, dict):
            continue
        text = (ex.get("text") or "").strip()
        if text and len(text) <= MAX_EXAMPLE_CHARS:
            return text
    return None


def build(input_path: str, en_input: str | None, lang: str, out_dir: str) -> str:
    os.makedirs(out_dir, exist_ok=True)
    sqlite_path = os.path.join(out_dir, f"dict_{lang}.sqlite")
    if os.path.exists(sqlite_path):
        os.remove(sqlite_path)

    conn = sqlite3.connect(sqlite_path)
    cur = conn.cursor()
    cur.executescript(
        """
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE lemmas(id INTEGER PRIMARY KEY, key TEXT, lemma TEXT, pos TEXT, gender TEXT);
        CREATE TABLE senses(lemma_id INTEGER, seq INTEGER, gloss TEXT, labels TEXT, example TEXT);
        CREATE TABLE en_senses(lemma_id INTEGER, seq INTEGER, gloss TEXT);
        -- forms_raw resolves to lemma ids by (base word, pos) after all lemmas are known.
        CREATE TABLE forms_raw(key TEXT, base_key TEXT, base_pos TEXT, tags TEXT);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        """
    )

    # (key, pos) -> lemma id, so repeated entries (etymologies) merge into one headword.
    lemma_ids: dict[tuple[str, str | None], int] = {}
    # key -> the lemma ids sharing it, to place an English entry whose pos the other edition spells
    # differently (or not at all) when there is only one candidate to place it on.
    ids_by_key: dict[str, list[int]] = {}
    lemma_pos: dict[int, str | None] = {}
    sense_counts: dict[int, int] = {}
    en_counts: dict[int, int] = {}
    # noun lemma id -> gender sense counts, summed across both editions and voted on at the end by
    # the same rule cards_normalize.py applies to the deck (cardtool.wiktionary.gender_of).
    gender_counts: dict[int, dict[str, int]] = {}
    next_id = 1
    n_glosses = n_en_glosses = n_forms_raw = n_labels = n_examples = 0

    def ingest(path: str, take_glosses: bool, english: bool = False):
        """Reads one extract.

        [take_glosses] writes the target-language senses and *creates* the headwords -- only the
        first pass does that, so the pack's headword set is exactly what the monolingual edition
        knows. [english] writes English glosses onto headwords that pass already created; an
        English-only word is skipped rather than added, which keeps the pack's size tied to the
        edition that supplies its primary definitions.

        Both passes contribute inflections: the English edition has by far the better conjugation
        and declension tables, which is why it was already being read before it supplied glosses.
        """
        nonlocal next_id, n_glosses, n_en_glosses, n_forms_raw, n_labels, n_examples
        for obj in iter_entries(path, lang):
            word = obj["word"]
            pos = obj.get("pos")
            key = normalize(word)
            if not key:
                continue

            lemma_id = lemma_ids.get((key, pos))
            # Which headword this pass's English glosses belong to. Part of speech is the join,
            # since sense numbering isn't comparable across editions; when the two spell a pos
            # differently the headword's sole entry takes them, but only when it *is* sole --
            # otherwise "banco"'s bank/bench senses would land on its Argentine interjection.
            english_target = lemma_id
            if english and english_target is None:
                candidates = ids_by_key.get(key) or []
                if len(candidates) == 1 and not verb_mismatch(pos, lemma_pos.get(candidates[0])):
                    english_target = candidates[0]

            for sense in obj.get("senses", []):
                # An inflected-form entry points back to its lemma.
                for ref in (sense.get("form_of") or []) + (sense.get("alt_of") or []):
                    base = ref.get("word") if isinstance(ref, dict) else None
                    if base:
                        cur.execute(
                            "INSERT INTO forms_raw(key, base_key, base_pos, tags) VALUES (?,?,?,?)",
                            (key, normalize(base), pos, join_tags(sense.get("tags"))),
                        )
                        n_forms_raw += 1
                if take_glosses:
                    labels = sense_labels(sense)
                    example = sense_example(sense)
                    for gloss in sense.get("glosses") or []:
                        gloss = (gloss or "").strip()
                        if not gloss:
                            continue
                        if lemma_id is None:
                            lemma_id = next_id
                            next_id += 1
                            lemma_ids[(key, pos)] = lemma_id
                            ids_by_key.setdefault(key, []).append(lemma_id)
                            lemma_pos[lemma_id] = pos
                            cur.execute(
                                "INSERT INTO lemmas(id, key, lemma, pos) VALUES (?,?,?,?)",
                                (lemma_id, key, word, pos),
                            )
                        seq = sense_counts.get(lemma_id, 0)
                        if seq >= MAX_SENSES:
                            continue
                        cur.execute(
                            "INSERT INTO senses(lemma_id, seq, gloss, labels, example) VALUES (?,?,?,?,?)",
                            (lemma_id, seq, gloss, labels or None, example),
                        )
                        sense_counts[lemma_id] = seq + 1
                        n_glosses += 1
                        n_labels += 1 if labels else 0
                        n_examples += 1 if example else 0
                elif english and english_target is not None and not is_form_of(sense):
                    # "third-person singular of casar" is an English string but not an English
                    # meaning; the form it describes is already covered by the forms table.
                    for gloss in sense.get("glosses") or []:
                        gloss = (gloss or "").strip()
                        if not gloss:
                            continue
                        seq = en_counts.get(english_target, 0)
                        if seq >= MAX_SENSES:
                            continue
                        cur.execute(
                            "INSERT INTO en_senses(lemma_id, seq, gloss) VALUES (?,?,?)",
                            (english_target, seq, gloss),
                        )
                        en_counts[english_target] = seq + 1
                        n_en_glosses += 1

            # Gender, from headword senses only: "plural of gato" is tagged masculine too, but it is
            # the plural's row, not a noun in its own right. Stored on nouns alone -- an adjective's
            # masculine/feminine tags describe its forms, not an article it takes.
            gender_target = english_target if english else lemma_ids.get((key, pos))
            if pos == "noun" and gender_target is not None and lemma_pos.get(gender_target) == "noun":
                counts = gender_counts.setdefault(gender_target, {"M": 0, "F": 0, "PL": 0, "G": 0})
                for sense in obj.get("senses", []):
                    if is_form_of(sense):
                        continue
                    tags = gender_tags(obj, sense)
                    counts["M"] += "masculine" in tags
                    counts["F"] += "feminine" in tags
                    # Plural-only (gafas, víveres) is the English edition's call alone: the Spanish
                    # edition tags gender but never number, so its senses would outvote every one.
                    if english and tags & {"masculine", "feminine"}:
                        counts["G"] += 1
                        counts["PL"] += "plural-only" in tags

            # Inflections listed under the lemma (the conjugation/declension tables).
            for form in obj.get("forms", []):
                f = form.get("form") if isinstance(form, dict) else None
                if not f or f == "-":
                    continue
                fkey = normalize(f)
                if not fkey or fkey == key:
                    continue
                cur.execute(
                    "INSERT INTO forms_raw(key, base_key, base_pos, tags) VALUES (?,?,?,?)",
                    (fkey, key, pos, join_tags(form.get("tags"))),
                )
                n_forms_raw += 1

    ingest(input_path, take_glosses=True)
    if en_input:
        ingest(en_input, take_glosses=False, english=True)

    print(f"  ingested: lemmas={len(lemma_ids)}  glosses={n_glosses}  forms(raw)={n_forms_raw}")
    print(f"            english glosses={n_en_glosses} on {len(en_counts)} headwords"
          f"  labels={n_labels}  examples={n_examples}")

    genders = [(g, lid) for lid, counts in gender_counts.items() if (g := gender_of(counts))]
    cur.executemany("UPDATE lemmas SET gender = ? WHERE id = ?", genders)
    n_nouns = sum(1 for pos in lemma_pos.values() if pos == "noun")
    by_code: dict[str, int] = {}
    for g, _ in genders:
        by_code[g] = by_code.get(g, 0) + 1
    print(f"            genders={len(genders)} of {n_nouns} nouns  "
          + "  ".join(f"{k}={v}" for k, v in sorted(by_code.items())))

    # Resolve forms to lemma ids (prefer a pos match, else any lemma sharing the base key), then
    # de-duplicate. Drop forms whose base lemma has no definition to show.
    cur.execute("CREATE INDEX idx_lemmas_lookup ON lemmas(key, pos)")
    cur.executescript(
        """
        CREATE TABLE forms(key TEXT, lemma_id INTEGER, tags TEXT);
        INSERT INTO forms(key, lemma_id, tags)
        SELECT fr.key,
               COALESCE(
                 (SELECT l.id FROM lemmas l WHERE l.key = fr.base_key AND l.pos = fr.base_pos LIMIT 1),
                 (SELECT l.id FROM lemmas l WHERE l.key = fr.base_key LIMIT 1)
               ) AS lemma_id,
               fr.tags
        FROM forms_raw fr
        WHERE lemma_id IS NOT NULL;
        DROP TABLE forms_raw;
        DELETE FROM forms WHERE rowid NOT IN (SELECT MIN(rowid) FROM forms GROUP BY key, lemma_id, tags);
        DROP INDEX idx_lemmas_lookup;
        CREATE INDEX idx_lemmas_key ON lemmas(key);
        CREATE INDEX idx_senses_lemma ON senses(lemma_id);
        CREATE INDEX idx_en_senses_lemma ON en_senses(lemma_id);
        CREATE INDEX idx_forms_key ON forms(key);
        """
    )
    n_forms = cur.execute("SELECT COUNT(*) FROM forms").fetchone()[0]
    print(f"  resolved: forms={n_forms}")

    cur.executemany(
        "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)",
        [
            ("lang", lang),
            ("schema", "4"),
            ("built", _dt.date.today().isoformat()),
            ("lemmas", str(len(lemma_ids))),
            ("glosses", str(n_glosses)),
            ("en_glosses", str(n_en_glosses)),
            ("labels", str(n_labels)),
            ("examples", str(n_examples)),
            ("forms", str(n_forms)),
            ("genders", str(len(genders))),
        ],
    )
    conn.commit()
    cur.execute("VACUUM")
    conn.commit()
    conn.close()

    gz_path = sqlite_path + ".gz"
    with open(sqlite_path, "rb") as src, gzip.open(gz_path, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst)

    print(f"  wrote {sqlite_path} ({os.path.getsize(sqlite_path)/1e6:.1f} MB)")
    print(f"  wrote {gz_path} ({os.path.getsize(gz_path)/1e6:.1f} MB)  <- upload this")
    return gz_path


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", required=True, help="JSONL(.gz) extract with target-language definitions")
    ap.add_argument("--lang", required=True, help="BCP-47 / Wiktextract lang_code to keep, e.g. 'es'")
    ap.add_argument(
        "--en-input",
        "--forms-input",  # its pre-schema-3 name, when it only contributed inflections
        dest="en_input",
        help="English-edition JSONL(.gz) for the same language: English glosses + richer inflections",
    )
    ap.add_argument("--output", default="dist", help="output directory (default: dist)")
    args = ap.parse_args(argv)

    print(f"Building {args.lang} dictionary from {args.input}")
    if args.en_input:
        print(f"  English glosses from {args.en_input}")
    build(args.input, args.en_input, args.lang, args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
