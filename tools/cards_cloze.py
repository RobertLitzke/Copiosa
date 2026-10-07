#!/usr/bin/env python3
"""Bank every eligible example sentence into the cloze bank, offline.

    python cards_cloze.py                 # dry run: what would be banked, and why the rest wasn't
    python cards_cloze.py --apply

**Why this exists.** ``ContextSentenceSource.mine`` walks ``flashcardDao.allActive`` from the top
and stops at ``limitPerSource`` (``ClozeViewModel.STOCK_PER_RUN`` = 60) with no progress cursor, so
every run -- the "Find more sentences" button and the six-hourly ``ClozeMineTask`` alike -- re-offers
the same head of the deck. Once those are banked or refused the bank stops growing, however many
example sentences the deck holds. Measured on 2026-09-02: 4,226 sentences in the deck, bank stuck
at 55, every further run reporting "Added 0".

This walks the whole deck once. The gate is a faithful port (``cardtool/clozegate.py``), so it
banks exactly what the app would have banked had it got that far -- no looser, no stricter.
"""

from __future__ import annotations

import argparse
import collections
import csv
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cardtool import clozegate as gate  # noqa: E402
from cardtool.db import connect, target_side  # noqa: E402
from cardtool.functionwords import SPANISH  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "dbpull"
LEMMAS = REPO / "tools" / "es_lemmas2000.tsv"
REPORT = WORK / "reports" / "cloze_mine.csv"

# ClozeMineContext.kt
PER_LEMMA_CAP = 3
BANK_MAX_PER_LANGUAGE = 1_500


def load_frequency(path: Path) -> dict[str, tuple[int, str, str]]:
    """form -> (rank, lemma, pack pos), the same index FrequencyList.matchOfForm reads."""
    index: dict[str, tuple[int, str, str]] = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            rank, lemma, pos = int(parts[0]), parts[1], parts[2]
            forms = parts[3].split(",") if len(parts) > 3 else []
            for form in [lemma, *forms]:
                key = gate.normalize_word(form)
                # First writer wins, so the higher-ranked headword keeps a shared form.
                if key and key not in index:
                    index[key] = (rank, lemma, pos)
    return index


def forms_for(row) -> set[str]:
    """The card's cached surface forms; the lemma alone when the cache is empty."""
    raw = row["inflectedForms"]
    forms = {gate.normalize_word(f) for f in raw.split(";")} if raw else set()
    forms.discard("")
    return forms or {gate.normalize_word(target_side(row) or "")} - {""}


def candidate_for(sentence: str, forms: set[str]) -> tuple[str, int] | None:
    """The first token of the sentence that is one of the card's forms: (answer, offset)."""
    for match in gate._TOKEN.finditer(sentence):
        if gate.normalize_word(match.group()) in forms:
            return match.group(), match.start()
    return None


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--db", default=str(WORK / "copiosa.db"))
    parser.add_argument("--lang", default="es")
    parser.add_argument("--apply", action="store_true", help="write the rows (default: dry run)")
    parser.add_argument("--limit", type=int, default=BANK_MAX_PER_LANGUAGE,
                        help=f"bank ceiling, per ClozeMineContext (default {BANK_MAX_PER_LANGUAGE})")
    args = parser.parse_args()

    conn = connect(args.db, readonly=not args.apply)
    freq = load_frequency(LEMMAS)

    cards = conn.execute(
        "SELECT * FROM flashcards WHERE targetLanguage = ? AND suspended = 0 ORDER BY dueAt, id",
        (args.lang,),
    ).fetchall()

    # Everything the gate needs about the learner, built once (ClozeMineContext).
    known_forms: set[str] = set()
    for row in cards:
        known_forms |= forms_for(row)

    banked = collections.Counter()
    existing_keys = set()
    for row in conn.execute(
        "SELECT lemma, textKey FROM cloze_sentences WHERE targetLanguage = ?", (args.lang,)
    ):
        banked[row["lemma"]] += 1
        existing_keys.add((row["lemma"], row["textKey"]))
    bank_size = sum(banked.values())

    print(f"{len(cards):,} active cards, {len(freq):,} frequency forms, "
          f"{len(known_forms):,} known forms, bank holds {bank_size:,}")

    now = int(time.time() * 1000)
    rows: list[tuple] = []
    reasons = collections.Counter()
    skipped = collections.Counter()
    added_per_lemma = collections.Counter()

    for card in cards:
        if bank_size + len(rows) >= args.limit:
            skipped["bank at ceiling"] += 1
            continue
        context = (card["contextSentence"] or "").strip()
        if not context:
            continue
        lemma = gate.normalize_word(target_side(card) or "")
        if not lemma:
            continue
        if banked[lemma] + added_per_lemma[lemma] >= PER_LEMMA_CAP:
            skipped["lemma already at its cap of 3"] += 1
            continue

        forms = forms_for(card)
        # One context field can hold more than one sentence; take the first that uses the word.
        chosen = None
        for sentence in gate.split_sentences(context):
            hit = candidate_for(sentence, forms)
            if hit:
                chosen = (sentence, *hit)
                break
        if chosen is None:
            reasons["ANSWER_NOT_FOUND (before gate)"] += 1
            continue

        sentence, answer, offset = chosen
        why = gate.reject(
            sentence, answer, card["id"], card["partOfSpeech"], known_forms, freq, SPANISH
        )
        if why:
            reasons[why] += 1
            continue

        key = gate.key_of(sentence)
        if (lemma, key) in existing_keys:
            # Both direction-halves of an item carry the same sentence, so the second one collides
            # on (lemma, textKey) exactly as the app's INSERT OR IGNORE would.
            skipped["duplicate row (mostly the item's other direction)"] += 1
            continue
        existing_keys.add((lemma, key))
        added_per_lemma[lemma] += 1
        rows.append((
            args.lang, gate.markup_for(sentence, answer, offset), answer, lemma, key,
            card["id"], None, None, "context", 0, None, now, 0.0, None, None, None,
            0, 0, None, "NEW", None, None, now,
        ))

    print(f"\n  would bank: {len(rows):,}")
    for reason, count in reasons.most_common():
        print(f"  refused {count:>6,}  {reason}")
    for reason, count in skipped.most_common():
        print(f"  skipped {count:>6,}  {reason}")

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    with REPORT.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["cardId", "lemma", "answer", "text"])
        for row in rows:
            writer.writerow([row[5], row[3], row[2], row[1]])
    print(f"  report: {REPORT}")

    if not args.apply:
        print("\ndry run -- nothing written. Re-run with --apply")
        return 0

    conn.executemany(
        """
        INSERT OR IGNORE INTO cloze_sentences
            (targetLanguage, text, answer, lemma, textKey, cardId, translation, distractors,
             source, suspended, flaggedReason, dueAt, intervalDays, stability, difficulty,
             fsrsStep, repetitions, lapses, lastReviewedAt, state, ankiNoteGuid, ankiOrd, createdAt)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        rows,
    )
    conn.commit()
    total = conn.execute(
        "SELECT count(*) FROM cloze_sentences WHERE targetLanguage = ?", (args.lang,)
    ).fetchone()[0]
    integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
    conn.close()
    print(f"\nbank now holds {total:,}; integrity_check: {integrity}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
