#!/usr/bin/env python3
"""Build an offline dictionary pack (SQLite, gzipped) for Copiosa from a Wiktextract dump.

The app (com.copiosa.data.translate.LocalDictionary) downloads one
``dict_<lang>.sqlite.gz`` per language and queries it entirely offline. This script turns a
kaikki.org / Wiktextract JSONL extract into that pack, keeping only what the app needs:

  * monolingual definitions (glosses) + part of speech, and
  * an inflected-form -> lemma index (conjugations, plurals, ...), so selecting "vivimos"
    resolves to "vivir".

Recommended source for Spanish *Spanish-language* definitions: the Spanish-edition extract,
e.g. https://kaikki.org/eswiktionary/  (download the per-language "Español" JSONL). That gives
glosses in Spanish. The English-edition extract (https://kaikki.org/dictionary/Spanish/) has the
richest inflection tables but English glosses; pass it via --forms-input to enrich conjugations.

Usage:
    python build_dictionary.py --input eswiktionary-es.jsonl.gz --lang es --output dist/
    # optionally merge richer inflections from the English-edition Spanish extract:
    python build_dictionary.py --input eswiktionary-es.jsonl.gz --lang es \
        --forms-input enwiktionary-Spanish.jsonl.gz --output dist/

Produces:  dist/dict_es.sqlite  and  dist/dict_es.sqlite.gz   (upload the .gz to GitHub Releases)

Schema (kept in sync with LocalDictionary.kt):
    lemmas(id INTEGER PRIMARY KEY, key TEXT, lemma TEXT, pos TEXT)  -- one row per headword+pos
    senses(lemma_id INTEGER, seq INTEGER, gloss TEXT)              -- one row per sense, capped
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

# Mirror LocalDictionary.normalize() so stored keys match the app's lookups.
_STRIP = "¿?¡!.,;:\"'«»()—–"

# Keep the first few senses per headword; the long tail of rare senses is what bloats the pack.
MAX_SENSES = 6


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


def build(input_path: str, forms_input: str | None, lang: str, out_dir: str) -> str:
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
        CREATE TABLE lemmas(id INTEGER PRIMARY KEY, key TEXT, lemma TEXT, pos TEXT);
        CREATE TABLE senses(lemma_id INTEGER, seq INTEGER, gloss TEXT);
        -- forms_raw resolves to lemma ids by (base word, pos) after all lemmas are known.
        CREATE TABLE forms_raw(key TEXT, base_key TEXT, base_pos TEXT, tags TEXT);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        """
    )

    # (key, pos) -> lemma id, so repeated entries (etymologies) merge into one headword.
    lemma_ids: dict[tuple[str, str | None], int] = {}
    sense_counts: dict[int, int] = {}
    next_id = 1
    n_glosses = n_forms_raw = 0

    def ingest(path: str, take_glosses: bool):
        nonlocal next_id, n_glosses, n_forms_raw
        for obj in iter_entries(path, lang):
            word = obj["word"]
            pos = obj.get("pos")
            key = normalize(word)
            if not key:
                continue

            lemma_id = lemma_ids.get((key, pos))

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
                    for gloss in sense.get("glosses") or []:
                        gloss = (gloss or "").strip()
                        if not gloss:
                            continue
                        if lemma_id is None:
                            lemma_id = next_id
                            next_id += 1
                            lemma_ids[(key, pos)] = lemma_id
                            cur.execute(
                                "INSERT INTO lemmas(id, key, lemma, pos) VALUES (?,?,?,?)",
                                (lemma_id, key, word, pos),
                            )
                        seq = sense_counts.get(lemma_id, 0)
                        if seq >= MAX_SENSES:
                            continue
                        cur.execute(
                            "INSERT INTO senses(lemma_id, seq, gloss) VALUES (?,?,?)",
                            (lemma_id, seq, gloss),
                        )
                        sense_counts[lemma_id] = seq + 1
                        n_glosses += 1

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
    if forms_input:
        ingest(forms_input, take_glosses=False)

    print(f"  ingested: lemmas={len(lemma_ids)}  glosses={n_glosses}  forms(raw)={n_forms_raw}")

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
        CREATE INDEX idx_forms_key ON forms(key);
        """
    )
    n_forms = cur.execute("SELECT COUNT(*) FROM forms").fetchone()[0]
    print(f"  resolved: forms={n_forms}")

    cur.executemany(
        "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)",
        [
            ("lang", lang),
            ("schema", "2"),
            ("built", _dt.date.today().isoformat()),
            ("lemmas", str(len(lemma_ids))),
            ("glosses", str(n_glosses)),
            ("forms", str(n_forms)),
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
    ap.add_argument("--forms-input", help="optional second JSONL(.gz) to enrich inflections only")
    ap.add_argument("--output", default="dist", help="output directory (default: dist)")
    args = ap.parse_args(argv)

    print(f"Building {args.lang} dictionary from {args.input}")
    build(args.input, args.forms_input, args.lang, args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
