"""Part of speech and gender for Spanish words, mined from the English Wiktionary dump.

``data/enwiktionary-Spanish.jsonl.gz`` is already in the repo for ``build_dictionary.py``. Its
noun entries carry gender (``senses[].tags`` -> ``masculine`` / ``feminine``), which makes it the
accurate source for backfilling the deck's empty ``partOfSpeech`` / ``gender`` columns -- far
better than guessing from word endings. The pack stores the same verdict (see [gender_of]), so
cards saved in the app get theirs at save time; this is for the deck that predates that.

Inflected forms ("form-of" senses) are counted separately from lemma senses, so *mesa* reads as
an unambiguous noun rather than a noun/verb tie with the conjugated form of *mesar*.

Accents are load-bearing here and the index keys on them: *cantó* is a verb, *canto* is a noun,
and folding the two together silently turned a shelf of conjugation cards into masculine nouns.
An accent-blind match is only tried for a query that has no accents of its own, and only when it
lands on exactly one headword.
"""

from __future__ import annotations

import gzip
import json
import unicodedata
from collections import defaultdict
from pathlib import Path

# Wiktionary's pos values -> the editor's vocabulary (EditCardDialog.kt).
POS_MAP = {
    "noun": "noun",
    "verb": "verb",
    "adj": "adjective",
    "adv": "adverb",
    "phrase": "phrase",
    "proverb": "phrase",
    "prep_phrase": "phrase",
    "intj": "expression",
    "pron": "grammar",
    "prep": "grammar",
    "conj": "grammar",
    "det": "grammar",
    "article": "grammar",
    "particle": "grammar",
    "num": "grammar",
}

# Proper names are deliberately absent: mapping them to "noun" made lowercase deck words collide
# with place names and surnames (a card for *tomé* matched the town Tomé).

# Senses carrying one of these describe an inflected form, not a headword.
_FORM_TAGS = {"form-of", "participle", "gerund", "plural", "alt-of", "abbreviation"}


def norm(text: str) -> str:
    """The exact index key: lowercased, accents intact."""
    return text.strip().lower()


def fold(text: str) -> str:
    """Accent-blind key, used only as a guarded fallback -- see the module docstring."""
    lowered = unicodedata.normalize("NFD", norm(text))
    return "".join(ch for ch in lowered if unicodedata.category(ch) != "Mn")


def has_accent(text: str) -> bool:
    return fold(text) != norm(text)


class PosGenderIndex:
    """word -> {pos: {"lemma": n, "form": n, "M": n, "F": n}}, built once and cached as JSON."""

    def __init__(self, entries: dict[str, dict], aliases: dict[str, list[str]] | None = None):
        self.entries = entries
        self.aliases = aliases if aliases is not None else _build_aliases(entries)

    # -- building ---------------------------------------------------------

    @classmethod
    def build(cls, dump: Path) -> "PosGenderIndex":
        entries: dict[str, dict] = defaultdict(dict)
        with gzip.open(dump, "rt", encoding="utf-8") as handle:
            for line in handle:
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                word = entry.get("word")
                raw_pos = entry.get("pos")
                if not word or raw_pos not in POS_MAP:
                    continue
                pos = POS_MAP[raw_pos]
                senses = entry.get("senses") or []
                lemma_senses = 0
                form_senses = 0
                masculine = 0
                feminine = 0
                for sense in senses:
                    tags = set(sense.get("tags") or [])
                    if tags & _FORM_TAGS:
                        form_senses += 1
                        continue
                    lemma_senses += 1
                    # Counted, not just collected: "mano" has ten feminine senses and two
                    # masculine slang ones, and it is "la mano", not "el/la mano".
                    if "masculine" in tags:
                        masculine += 1
                    if "feminine" in tags:
                        feminine += 1
                if not lemma_senses and not form_senses:
                    continue
                bucket = entries[norm(word)].setdefault(
                    pos, {"lemma": 0, "form": 0, "M": 0, "F": 0}
                )
                bucket["lemma"] += lemma_senses
                bucket["form"] += form_senses
                bucket["M"] += masculine
                bucket["F"] += feminine
        return cls(dict(entries))

    @classmethod
    def load_or_build(cls, dump: Path, cache: Path) -> "PosGenderIndex":
        if cache.exists() and cache.stat().st_mtime >= dump.stat().st_mtime:
            payload = json.loads(cache.read_text(encoding="utf-8"))
            return cls(payload["entries"], payload["aliases"])
        index = cls.build(dump)
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(
            json.dumps(
                {"entries": index.entries, "aliases": index.aliases}, ensure_ascii=False
            ),
            encoding="utf-8",
        )
        return index

    # -- querying ---------------------------------------------------------

    def lookup(self, word: str) -> dict[str, dict] | None:
        """Exact match first; an accent-blind retry only for an unaccented, unambiguous query."""
        exact = self.entries.get(norm(word))
        if exact is not None:
            return exact
        if has_accent(word):
            # The card spelled the accent; if the dump has no such headword, a folded match
            # would be a different word (canto vs cantó). Refuse rather than guess.
            return None
        candidates = self.aliases.get(fold(word)) or []
        if len(candidates) == 1:
            return self.entries.get(candidates[0])
        return None

    def resolve(self, word: str, hint_pos: str | None = None) -> tuple[str | None, str | None]:
        """Best (pos, gender) for a word, or (None, None) when the dump can't settle it.

        ``hint_pos`` breaks a tie from outside evidence -- an English gloss reading "(to) fall"
        says verb, an existing gender says noun. Without a hint, a word whose senses split
        across several parts of speech is left alone rather than guessed at.
        """
        by_pos = self.lookup(word)
        if not by_pos:
            return None, None

        # A part of speech counts as "real" for this word only if it has headword senses.
        lemma_pos = {pos: b for pos, b in by_pos.items() if b["lemma"] > 0}
        candidates = lemma_pos or by_pos

        if hint_pos and hint_pos in candidates:
            pos = hint_pos
        elif len(candidates) == 1:
            pos = next(iter(candidates))
        elif {"noun", "adjective"} <= set(candidates):
            # Spanish adjectives nominalise freely, so sense counts don't separate these two:
            # *flaco*, *plana* and *chico* all read as dominant nouns and are adjectives on the
            # card. Refuse, and let the LLM round-trip decide.
            return None, None
        else:
            pos = _dominant(candidates, lambda bucket: bucket["lemma"])
            if pos is None:
                # A genuine split -- "dulce" is adjective and noun in about equal measure.
                # Leave it for the LLM round-trip rather than guessing.
                return None, None

        if pos != "noun":
            return pos, None
        # Gender is only offered when nothing else claims the word. A word that is also an
        # adjective or a verb form gets its part of speech but no article chip -- a wrong chip
        # ("el plana") is worse than a missing one.
        if len(candidates) > 1:
            return pos, None
        return pos, gender_of(candidates[pos])


# One reading wins only if it clearly outweighs every rival; a near-tie is left unresolved.
DOMINANCE = 4
MIN_DOMINANT = 3


def _dominant(candidates: dict[str, dict], weight) -> str | None:
    """The one clearly-winning key, or None when the field is close."""
    ranked = sorted(candidates, key=lambda k: weight(candidates[k]), reverse=True)
    top = weight(candidates[ranked[0]])
    runner_up = weight(candidates[ranked[1]]) if len(ranked) > 1 else 0
    if top >= MIN_DOMINANT and top >= DOMINANCE * max(runner_up, 1):
        return ranked[0]
    return None


def gender_of(bucket: dict) -> str | None:
    """M, F, MF, MPL, FPL or None -- by sense count, so a rare regional reading can't flip a word.

    Shared with ``build_dictionary.py``, which stores the same verdict in the pack. A bucket may
    also carry ``PL`` (senses tagged plural-only) and ``G`` (senses tagged with any gender); a word
    is plural-only (*gafas*, *víveres*) only when every gendered sense says so.
    """
    masculine, feminine = bucket.get("M", 0), bucket.get("F", 0)
    if not masculine and not feminine:
        return None
    if not feminine:
        gender = "M"
    elif not masculine:
        gender = "F"
    elif masculine >= DOMINANCE * feminine:
        gender = "M"
    elif feminine >= DOMINANCE * masculine:
        gender = "F"
    else:
        return "MF"
    plural_only = bucket.get("PL", 0)
    if plural_only and plural_only >= bucket.get("G", 0):
        return gender + "PL"
    return gender


def _build_aliases(entries: dict[str, dict]) -> dict[str, list[str]]:
    """folded spelling -> the headwords that fold to it, for the guarded accent-blind retry."""
    aliases: dict[str, list[str]] = defaultdict(list)
    for word in entries:
        folded = fold(word)
        if folded != word:
            aliases[folded].append(word)
    return dict(aliases)
