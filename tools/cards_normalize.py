#!/usr/bin/env python3
"""Backfill partOfSpeech and gender on the pulled deck, in two reviewable tiers.

    python cards_normalize.py                 # dry run; writes dbpull/reports/normalize.csv
    python cards_normalize.py --apply a       # only the marker rewrites (small, obvious)
    python cards_normalize.py --apply a b     # plus the dictionary backfill (the big one)

**Tier A -- markers in the card text.** A trailing ``(adj)``/``(nf)`` or a leading ``(la)`` is
annotation the structured columns replace: the value moves into the column and the marker is
deleted from the text.

**Tier B -- dictionary backfill.** Looks the target word up in the English Wiktionary dump
(``cardtool/wiktionary.py``) and fills what is still empty. Ambiguous words are left alone unless
the card itself disambiguates -- an existing gender, or a gloss reading "(to) fall" / "(the)
smoke". Whatever survives unresolved is handed to the LLM round-trip in cards_sentences.py.

Both tiers only ever *fill gaps*: an existing part of speech or gender is never overwritten.
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cardtool import markers  # noqa: E402
from cardtool.db import (  # noqa: E402
    GENDER_VALUES, POS_VALUES, connect, gloss_side, missing_pos, set_fields, target_side,
)
from cardtool.wiktionary import PosGenderIndex  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "dbpull"
DUMP = REPO / "data" / "enwiktionary-Spanish.jsonl.gz"
CACHE = WORK / "es_pos_gender.json"
REPORT = WORK / "reports" / "normalize.csv"

FIELDS = [
    "tier", "id", "source", "target", "gloss",
    "old_front", "new_front", "old_back", "new_back",
    "old_pos", "new_pos", "old_gender", "new_gender",
]


class Change:
    """One proposed edit to one card, plus why."""

    __slots__ = ("row", "tier", "source", "fields")

    def __init__(self, row, tier: str, source: str):
        self.row = row
        self.tier = tier
        self.source = source
        self.fields: dict[str, object] = {}

    def set(self, name: str, value) -> None:
        if value is not None and self.row[name] != value:
            self.fields[name] = value

    def __bool__(self) -> bool:
        return bool(self.fields)

    def as_csv_row(self) -> dict:
        row = self.row
        return {
            "tier": self.tier,
            "id": row["id"],
            "source": self.source,
            "target": target_side(row),
            "gloss": gloss_side(row),
            "old_front": row["front"],
            "new_front": self.fields.get("front", ""),
            "old_back": row["back"],
            "new_back": self.fields.get("back", ""),
            "old_pos": row["partOfSpeech"] or "",
            "new_pos": self.fields.get("partOfSpeech", ""),
            "old_gender": row["gender"] or "",
            "new_gender": self.fields.get("gender", ""),
        }


def tier_a(row) -> Change | None:
    """Markers in the text: parse them into the columns, then delete them."""
    change = Change(row, "A", "")
    reasons = []
    pos: str | None = None
    gender: str | None = None

    for side in ("front", "back"):
        cleaned, found_pos, found_gender = markers.strip_pos_markers(row[side] or "")
        if cleaned != (row[side] or ""):
            change.set(side, cleaned)
            reasons.append(f"pos-marker:{side}")
        pos = pos or found_pos
        gender = gender or found_gender

    # The leading article only makes sense on the target-language side of a headword.
    target_field = "front" if row["frontIsTarget"] else "back"
    current_target = change.fields.get(target_field, row[target_field] or "")
    stripped, article_gender = markers.strip_leading_article(current_target)
    if article_gender:
        change.set(target_field, stripped)
        gender = gender or article_gender
        pos = pos or "noun"
        reasons.append("article")

    if missing_pos(row["partOfSpeech"]) and pos:
        change.set("partOfSpeech", pos)
    if not row["gender"] and gender:
        change.set("gender", gender)

    change.source = ",".join(reasons)
    return change if change else None


def tier_b(row, index: PosGenderIndex) -> Change | None:
    """Dictionary backfill for whatever tier A left empty."""
    needs_pos = missing_pos(row["partOfSpeech"])
    needs_gender = not row["gender"]
    if not needs_pos and not needs_gender:
        return None

    target = target_side(row) or ""
    gloss = gloss_side(row) or ""
    if markers.looks_like_sentence(target):
        return None

    # A conjugation card is a verb form, full stop -- no lookup can improve on that, and a
    # lookup actively hurts: *traje*, *vino* and *tomé* are all real nouns as well.
    if row["cardKind"] == "CONJUGATION":
        change = Change(row, "B", "conjugation-kind")
        if needs_pos:
            change.set("partOfSpeech", "verb")
        return change or None

    hint = markers.gloss_hint(gloss) or markers.shape_hint(target, row["gender"], row["cardKind"])
    if hint == "phrase":
        # Multi-word items aren't in the dump as headwords often enough to be worth a lookup;
        # calling them phrases is both true and what the editor's own vocabulary says.
        change = Change(row, "B", "phrase-shape")
        if needs_pos:
            change.set("partOfSpeech", "phrase")
        return change or None

    pos, gender = index.resolve(target, hint_pos=hint)
    if pos is None:
        return None

    change = Change(row, "B", f"wiktionary{'+hint:' + hint if hint else ''}")
    if needs_pos:
        change.set("partOfSpeech", pos)
    # Never stamp a gender onto a card the user has already called something other than a noun,
    # nor onto a participle/diminutive the dump happens to list only as a noun.
    existing_pos = (row["partOfSpeech"] or "").strip().lower()
    if (
        needs_gender
        and pos == "noun"
        and existing_pos in ("", "unknown", "noun")
        and not markers.looks_adjectival(target)
    ):
        change.set("gender", gender)
    return change or None


def validate(change: Change) -> None:
    pos = change.fields.get("partOfSpeech")
    if pos is not None and pos not in POS_VALUES:
        raise ValueError(f"card {change.row['id']}: pos {pos!r} is outside the editor vocabulary")
    gender = change.fields.get("gender")
    if gender is not None and gender not in GENDER_VALUES:
        raise ValueError(f"card {change.row['id']}: gender {gender!r} is not a known code")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--db", default=str(WORK / "copiosa.db"))
    parser.add_argument("--apply", nargs="*", choices=["a", "b"], default=[],
                        help="tiers to write; omit for a dry run")
    parser.add_argument("--lang", default="es")
    args = parser.parse_args()

    conn = connect(args.db, readonly=not args.apply)
    rows = conn.execute(
        "SELECT * FROM flashcards WHERE targetLanguage = ? ORDER BY id", (args.lang,)
    ).fetchall()

    index = PosGenderIndex.load_or_build(DUMP, CACHE)
    print(f"{len(rows):,} cards, {len(index.entries):,} dictionary headwords")

    changes: list[Change] = []
    # Tier A first: it can supply a gender that then disambiguates tier B for the same card.
    applied_a: dict[int, Change] = {}
    for row in rows:
        change = tier_a(row)
        if change:
            validate(change)
            changes.append(change)
            applied_a[row["id"]] = change

    for row in rows:
        merged = dict(row)
        earlier = applied_a.get(row["id"])
        if earlier:
            merged.update(earlier.fields)
        change = tier_b(merged, index)
        if change:
            validate(change)
            changes.append(change)

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    with REPORT.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        for change in changes:
            writer.writerow(change.as_csv_row())

    by_tier: dict[str, int] = {}
    for change in changes:
        by_tier[change.tier] = by_tier.get(change.tier, 0) + 1
    print(f"tier A (markers):    {by_tier.get('A', 0):,} cards")
    print(f"tier B (dictionary): {by_tier.get('B', 0):,} cards")
    print(f"report: {REPORT}")

    if not args.apply:
        print("\ndry run -- nothing written. Review the report, then re-run with --apply a b")
        return 0

    wanted = {tier.upper() for tier in args.apply}
    written = 0
    for change in changes:
        if change.tier in wanted:
            set_fields(conn, change.row["id"], **change.fields)
            written += 1
    conn.commit()
    integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
    conn.close()
    print(f"\napplied tier(s) {sorted(wanted)} to {written:,} cards; integrity_check: {integrity}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
