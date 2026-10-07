#!/usr/bin/env python3
"""Round-trip example sentences (and leftover pos/gender) through an external LLM.

    python cards_sentences.py export          # writes dbpull/sentences/cards_NNN.json + PROMPT.md
    python cards_sentences.py export --batch-size 100
    ... hand each batch to an LLM, save its answer as cards_NNN.reply.json ...
    python cards_sentences.py import dbpull/sentences/*.reply.json

``contextSentence`` is the cloze bank's cheapest source (``ContextSentenceSource``), so filling it
is what lifts the bank off its floor.

The deck stores each vocabulary item twice -- once per review direction -- so the export groups
cards by (target, gloss) and asks for one sentence per *item*, not per row. That roughly halves
the batches, and both halves of a pair end up with the same example.
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import re
import sys
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cardtool.db import (  # noqa: E402
    GENDER_VALUES, POS_VALUES, connect, gloss_side, missing_pos, set_fields, target_side,
)

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "dbpull"
OUT = WORK / "sentences"
REJECTS = WORK / "reports" / "sentences_rejected.csv"
BATCH_SIZE = 200  # small enough that one batch fits comfortably in a chat context

# Match ClozeGate.MIN_WORDS / MAX_WORDS exactly. A sentence outside this range is accepted onto
# the card but can never become a cloze item, which is most of the point of writing it.
MIN_WORDS = 4
MAX_WORDS = 18


def fold(text: str) -> str:
    lowered = unicodedata.normalize("NFD", text.strip().lower())
    return "".join(ch for ch in lowered if unicodedata.category(ch) != "Mn")


def collect(conn, lang: str) -> list[dict]:
    """One entry per distinct (target, gloss), carrying every card id that shares it."""
    rows = conn.execute(
        """
        SELECT * FROM flashcards
        WHERE targetLanguage = ?
          AND suspended = 0
          AND cardKind IN ('VOCAB', 'PHRASE')
          AND (contextSentence IS NULL OR trim(contextSentence) = '')
        ORDER BY dueAt, id
        """,
        (lang,),
    ).fetchall()

    items: dict[tuple[str, str], dict] = {}
    for row in rows:
        target = (target_side(row) or "").strip()
        gloss = (gloss_side(row) or "").strip()
        if not target or not gloss:
            continue
        key = (fold(target), fold(gloss))
        item = items.get(key)
        if item is None:
            needs = ["example"]
            if missing_pos(row["partOfSpeech"]):
                needs.append("pos")
            item = {
                "id": row["id"],
                "ids": [],
                "term": target,
                "gloss": gloss,
                "pos": row["partOfSpeech"] if not missing_pos(row["partOfSpeech"]) else None,
                "gender": row["gender"],
                "needs": needs,
            }
            items[key] = item
        item["ids"].append(row["id"])
        # A noun with no gender still wants one, whichever half of the pair noticed first.
        if item["pos"] == "noun" and not item["gender"] and "gender" not in item["needs"]:
            item["needs"].append("gender")
    return list(items.values())


PROMPT = """# Example sentences for a Spanish flashcard deck

Each `cards_NNN.json` file holds up to {batch} vocabulary items from a personal Spanish deck.
For every item, write **one** example sentence in Spanish.

## Rules for the sentence

- Natural, everyday Spanish at roughly A2-B1 level.
- {min_words}-{max_words} words. One sentence, ending in a full stop (or ? / !).
- Do **not** start the sentence with the term itself -- put something before it.
- It must actually contain the item's `term`. Inflecting it is fine and expected -- conjugate a
  verb, pluralise or agree a noun or adjective.
- Make the sentence *disambiguate* the term: someone who knows the rest of the words should be
  able to guess this one. Avoid "El {{term}} es bueno." -- the surrounding words carry the meaning.
- Use the sense given in `gloss`, not another sense of the same word.
- No quotation marks around the sentence, no translation, no commentary.

## Also fill what is asked for

Each item lists what it `needs`. Beyond `example`:

- `pos` -- one of: noun, verb, adjective, adverb, phrase, expression, grammar, other.
- `gender` -- for nouns only: M, F, MF (used for both), MPL, FPL (plural-only nouns).

If an item does not list `pos` or `gender` in `needs`, leave that field out.

## Reply format

Reply with JSON only -- one object, same `batch` number, one entry per item, `id` copied exactly:

```json
{{"batch": 1, "cards": [
  {{"id": 1234, "example": "El gato duerme encima de la lavadora.", "pos": "noun", "gender": "M"}},
  {{"id": 1240, "example": "Tuve que zambullirme para recoger las llaves del fondo."}}
]}}
```

Save each reply next to its input as `cards_NNN.reply.json`.
"""


def is_reply_file(path: Path) -> bool:
    """Reply files are matched case-insensitively -- cards_001.REPLY.json is a reply too."""
    return ".reply." in path.name.lower()


def cmd_export(args: argparse.Namespace) -> int:
    conn = connect(args.db, readonly=True)
    items = collect(conn, args.lang)
    conn.close()

    size = args.batch_size

    OUT.mkdir(parents=True, exist_ok=True)
    for stale in OUT.glob("cards_*.json"):
        if not is_reply_file(stale):
            stale.unlink()

    batches = [items[i:i + size] for i in range(0, len(items), size)]
    for number, batch in enumerate(batches, start=1):
        payload = {
            "batch": number,
            "target_language": args.lang,
            "native_language": "en",
            "cards": batch,
        }
        path = OUT / f"cards_{number:03d}.json"
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")

    (OUT / "PROMPT.md").write_text(
        PROMPT.format(batch=size, min_words=MIN_WORDS, max_words=MAX_WORDS),
        encoding="utf-8",
    )

    rows = sum(len(item["ids"]) for item in items)
    needs_pos = sum(1 for item in items if "pos" in item["needs"])
    needs_gender = sum(1 for item in items if "gender" in item["needs"])
    print(f"{rows:,} cards -> {len(items):,} distinct items -> {len(batches)} file(s) in {OUT}")
    print(f"  also asking for pos on {needs_pos:,} items, gender on {needs_gender:,}")
    print(f"  hand {OUT / 'PROMPT.md'} to the LLM along with each cards_NNN.json")
    return 0


# A term written as a pattern ("Más X que Y") can\'t be checked against a sentence.
TEMPLATE = re.compile(r"\b[XY]\b")


def term_variants(term: str) -> list[str]:
    """The spellings a sentence could legitimately use for this term.

    Deck terms carry shapes a plain substring test trips over: a parenthetical aside
    ("(uno) mas", "conductor(a)"), slash alternatives ("camarero/mesero"), and reflexive
    infinitives whose sentence form moves the clitic ("cansarse de" -> "me cansé de").
    """
    cleaned = re.sub(r"\([^)]*\)|\[[^\]]*]", " ", term)
    variants = []
    for alternative in re.split(r"[/,;]", cleaned):
        alternative = " ".join(alternative.split())
        if not alternative:
            continue
        variants.append(alternative)
        # "cansarse de" also appears as "cansar de" once the clitic moves off the infinitive.
        without_clitic = re.sub(r"\b(\w+?)(ar|er|ir)se\b", r"\1\2", alternative)
        if without_clitic != alternative:
            variants.append(without_clitic)
    return variants


def contains_term(term: str, sentence: str) -> bool:
    """Does the sentence use the term, allowing for inflection?"""
    if TEMPLATE.search(term):
        return True
    folded_sentence = fold(sentence)
    for variant in term_variants(term):
        folded = fold(variant)
        if folded and folded in folded_sentence:
            return True
        # Verbs and agreeing adjectives change their ending; match on a generous stem
        # instead. Short words are skipped -- "a"/"de" in a phrasal term prove nothing.
        for word in folded.split():
            if len(word) < 4:
                continue
            stem = word[:-2] if len(word) > 5 else word[:-1]
            if stem and stem in folded_sentence:
                return True
    return False


def cmd_import(args: argparse.Namespace) -> int:
    exports = {}
    for path in sorted(OUT.glob("cards_*.json")):
        if is_reply_file(path):
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        for item in payload["cards"]:
            # An export item always carries the term and the ids that share it. Anything else in
            # this slot is a reply that slipped past the name filter, and would quietly shadow the
            # real item for that id.
            if "term" not in item or "ids" not in item:
                print(f"{path.name} looks like a reply, not an export -- skipping it",
                      file=sys.stderr)
                break
            exports[item["id"]] = item
    if not exports:
        print(f"no export files in {OUT} -- run `cards_sentences.py export` first", file=sys.stderr)
        return 1

    replies: list[dict] = []
    for pattern in args.files:
        for name in sorted(glob.glob(pattern)):
            payload = json.loads(Path(name).read_text(encoding="utf-8"))
            replies.extend(payload.get("cards", []))
    if not replies:
        print("no reply entries found", file=sys.stderr)
        return 1

    conn = connect(args.db)
    rejects: list[dict] = []
    written_examples = written_pos = written_gender = 0

    for reply in replies:
        item = exports.get(reply.get("id"))
        if item is None:
            rejects.append({"id": reply.get("id"), "term": "", "example": reply.get("example", ""),
                            "reason": "id not in any export batch"})
            continue

        example = (reply.get("example") or "").strip().strip('"').strip()
        fields: dict[str, object] = {}
        if example:
            words = len(example.split())
            if not (MIN_WORDS <= words <= MAX_WORDS):
                rejects.append({"id": item["id"], "term": item["term"], "example": example,
                                "reason": f"{words} words, wanted {MIN_WORDS}-{MAX_WORDS}"})
            elif not contains_term(item["term"], example) and not args.force:
                rejects.append({"id": item["id"], "term": item["term"], "example": example,
                                "reason": "sentence does not use the term"})
            else:
                fields["contextSentence"] = example

        pos = (reply.get("pos") or "").strip().lower()
        if pos and "pos" in item["needs"]:
            if pos in POS_VALUES:
                fields["partOfSpeech"] = pos
            else:
                rejects.append({"id": item["id"], "term": item["term"], "example": pos,
                                "reason": "pos outside the editor vocabulary"})

        gender = (reply.get("gender") or "").strip().upper()
        if gender and "gender" in item["needs"]:
            if gender in GENDER_VALUES:
                fields["gender"] = gender
            else:
                rejects.append({"id": item["id"], "term": item["term"], "example": gender,
                                "reason": "gender is not a known code"})

        if not fields:
            continue
        # Both direction-halves of the item get the same answer.
        for card_id in item["ids"]:
            set_fields(conn, card_id, **fields)
        if "contextSentence" in fields:
            written_examples += len(item["ids"])
        if "partOfSpeech" in fields:
            written_pos += len(item["ids"])
        if "gender" in fields:
            written_gender += len(item["ids"])

    conn.commit()
    integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
    conn.close()

    REJECTS.parent.mkdir(parents=True, exist_ok=True)
    with REJECTS.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["id", "term", "example", "reason"])
        writer.writeheader()
        writer.writerows(rejects)

    print(f"{len(replies):,} replies -> examples on {written_examples:,} cards, "
          f"pos on {written_pos:,}, gender on {written_gender:,}")
    print(f"{len(rejects):,} rejected; see {REJECTS}")
    print(f"integrity_check: {integrity}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--db", default=str(WORK / "copiosa.db"))
    parser.add_argument("--lang", default="es")
    sub = parser.add_subparsers(dest="command", required=True)
    exporter = sub.add_parser("export", help="write the JSON batches")
    exporter.add_argument("--batch-size", type=int, default=BATCH_SIZE,
                          help=f"items per file (default {BATCH_SIZE})")
    importer = sub.add_parser("import", help="apply the LLM's replies")
    importer.add_argument("files", nargs="+", help="reply JSON files (globs are fine)")
    importer.add_argument("--force", action="store_true",
                          help="accept a sentence even if it doesn't seem to use the term")
    args = parser.parse_args()
    return {"export": cmd_export, "import": cmd_import}[args.command](args)


if __name__ == "__main__":
    raise SystemExit(main())
