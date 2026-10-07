"""Build the bundled frequency-ranked vocabulary list.

The app wants to answer "which words are worth learning next" and "how much of the common
vocabulary do you already hold". A raw corpus list cannot answer either: OpenSubtitles counts
*surface forms*, so `es`, `esta'`, `estoy` and `estas` are four separate rows and roughly a sixth of
the head is function words nobody makes a flashcard of. This script turns that into a list of
content **lemmas** by lemmatising every surface form against the dictionary pack, rolling the counts
up onto the lemma, and dropping the closed-class words.

The interesting part is telling a base form apart from an inflection, because the pack gives every
inflected form its own `lemmas` row (`tengo` is a `lemmas` row with pos=verb, exactly like `tener`).
Getting that backwards is what turns `casa` into `casar` and `mercado` into `mercar`. See `resolve`.

Inputs
    data/<lang>_frequency_50k.txt        OpenSubtitles word counts (gitignored; see build_conjugations.py)
    dist/dict_<lang>.sqlite              Dictionary pack built by build_dictionary.py (gitignored)
    tools/frequency_overrides_<lang>.yaml  Curated stoplist / remaps / drops

Outputs
    tools/<lang>_lemmas2000.tsv          Committed derived list, so a rebuild needs no download
    app/src/test/resources/frequency_<lang>.tsv   Copy the JVM test replays against the Kotlin
    app/src/main/java/com/copiosa/domain/vocab/freq/data/FrequencyList<Lang>Data.kt
    tools/_verify/frequency_<lang>.json  Human-readable dump for eyeballing

Usage
    python tools/build_frequency.py
    python tools/build_frequency.py --lang es --check-only
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent

TOP_LEMMAS = 2000
LINES_PER_BLOCK = 500

# Languages with both a frequency corpus and a dictionary pack. `object` is the generated Kotlin
# object name; `title` only decorates the file header.
LANGUAGES = {
    "es": {"object": "FrequencyListEsData", "title": "Spanish"},
    "pt": {"object": "FrequencyListPtData", "title": "Portuguese"},
}

# Closed-class parts of speech. A surface form with *any* reading in this set is dropped outright:
# "para" is a preposition far more often than it is a form of "parar", and a learner does not want a
# flashcard for either reading. Being aggressive here costs a handful of real words and buys a list
# with no grammar particles in it.
FUNCTION_POS = {
    "prep", "postp", "conj", "pron", "article", "contraction", "particle", "det", "num",
    "symbol", "punct", "prefix", "suffix", "infix", "abbrev", "character", "syllable",
}

# Parts of speech worth a flashcard. "name" (proper nouns) is deliberately absent -- the corpus is
# subtitles, so it is thick with character names.
CONTENT_POS = {"noun", "verb", "adj", "adv", "intj", "phrase"}

# Tags that mark a purely nominal inflection: a plural or a feminine, not a conjugated verb. These
# collapse onto their parent ("gatos" -> "gato", "buenas" -> "bueno") even though the pack also
# carries a standalone `lemmas` row for them. Superlatives and diminutives are deliberately absent:
# "mejor" and "optimo" are words in their own right, not spellings of "bueno".
NOMINAL_INFLECTION_TAGS = {"plural", "singular", "feminine", "masculine"}

# Degree and size derivations. The pack tags them alongside a gender tag ("masculine superlative"),
# so they have to be excluded explicitly or "optimo" and "buenisimo" get swallowed by "bueno".
DERIVATION_TAGS = {"superlative", "comparative", "diminutive", "augmentative"}

# Portuguese negative imperatives are written as two words ("nao comeces"), and the extractor filed
# the bare negator as a form of every -ar verb. Left in, "nao" -- one of the commonest words in the
# language -- lands inside "comecar". No language's negative imperative adds a form worth keeping.
UNRELIABLE_TAGS = {"negative"}

# How many inflected forms an entry must own before it counts as a base form. The packs give some
# inflections a one-child stub paradigm ("esta" has exactly one), which a simple "has any child"
# test reads as evidence of a headword; a real verb has dozens.
MIN_PARADIGM = 5

# Which POS wins when a word is several things at once ("bien" is noun, adv and intj). Only breaks
# ties -- the number of documented senses decides first.
POS_PRIORITY = ["adv", "noun", "adj", "verb", "intj", "phrase"]

# How much more corpus support a parent lemma needs before it may claim a word that is also a
# headword itself. Without a bias, "tema" (topic) is swallowed by "temer" (to fear); with too much,
# "era" stays an epoch instead of being the imperfect of "ser". Tuned by reading _verify output.
SELF_BIAS = 2.5

# Passes of the resolve/roll-up loop. Pass 1 adjudicates on raw surface counts, later passes on the
# rolled-up lemma totals the previous pass produced -- which is what lets "ser", scattered across
# dozens of forms, outweigh the rare nouns "era", "son" and "ere" that spell three of them.
PASSES = 3


def strip_accents(word: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", word) if not unicodedata.combining(c))


def normalize(word: str) -> str:
    """Mirrors `HighlightTarget.normalizeWord`: lowercase, trim non-alphanumeric ends."""
    word = word.lower()
    start, end = 0, len(word)
    while start < end and not word[start].isalnum():
        start += 1
    while end > start and not word[end - 1].isalnum():
        end -= 1
    return word[start:end]


def read_frequency(path: Path) -> list[tuple[str, int]]:
    """The corpus list in rank order, as (word, count); same 2-column parse as build_conjugations."""
    out: list[tuple[str, int]] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            parts = line.split()
            if len(parts) == 2 and parts[1].isdigit():
                word = normalize(parts[0])
                # Single alphabetic tokens only: the corpus carries stray numerals and fragments.
                if word and word.isalpha():
                    out.append((word, int(parts[1])))
    return out


class Pack:
    """Read-only view of a dictionary pack, with the few lookups this script needs memoised."""

    def __init__(self, path: Path):
        self.db = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
        self._senses: dict[int, int] = {}
        # `forms` is indexed on `key` but not on `lemma_id`, so asking "how big is this entry's
        # paradigm" per word would scan two million rows each time. One pass up front instead.
        self._paradigm: Counter[int] = Counter(
            row[0] for row in self.db.execute("SELECT lemma_id FROM forms"))

    def lemma_rows(self, key: str) -> list[tuple[int, str, str]]:
        return self.db.execute(
            "SELECT id, lemma, pos FROM lemmas WHERE key = ?", (key,)).fetchall()

    def form_rows(self, key: str) -> list[tuple[str, str, str]]:
        """(parent lemma, parent pos, tags) for every entry that spells `key` as an inflection."""
        rows = self.db.execute(
            "SELECT l.lemma, l.pos, f.tags FROM forms f JOIN lemmas l ON l.id = f.lemma_id "
            "WHERE f.key = ?", (key,)).fetchall()
        return [r for r in rows if not (set((r[2] or "").split()) & UNRELIABLE_TAGS)]

    def has_paradigm(self, lemma_id: int) -> bool:
        """Whether this entry owns inflected forms -- i.e. it is a base form, not itself a form.

        This is the whole trick. The pack gives `tengo` its own `lemmas` row with pos=verb, so POS
        alone cannot distinguish it from `tener`; only `tener` has children in `forms`.
        """
        return self._paradigm[lemma_id] >= MIN_PARADIGM

    def sense_count(self, lemma_id: int) -> int:
        if lemma_id not in self._senses:
            row = self.db.execute(
                "SELECT COUNT(*) FROM senses WHERE lemma_id = ?", (lemma_id,)).fetchone()
            self._senses[lemma_id] = row[0] if row else 0
        return self._senses[lemma_id]


def pos_rank(pos: str) -> int:
    return POS_PRIORITY.index(pos) if pos in POS_PRIORITY else len(POS_PRIORITY)


def resolve(word: str, pack: Pack, support: dict[str, int], overrides: dict) -> tuple[str, str] | None:
    """Map one surface form to the (lemma, pos) it should count towards, or None to drop it.

    `support` scores how much corpus weight a lemma carries overall; see [PASSES].

    The order of the tests below is load-bearing; each one exists because of a word it got wrong:

    1. Overrides first, so a human decision is never re-litigated by the rules.
    2. Any closed-class reading drops the word ("para", "como", "esta").
    3. Nominal inflections collapse onto their parent ("gatos" -> "gato", "heridas" -> "herido"),
       ahead of the self test, because the pack also lists them as standalone lemmas.
    4. A word that *is* a base entry stays itself unless a parent is much better supported. This is
       what keeps "casa" a house rather than a form of "casar", while still letting "era" and "son"
       be read as forms of "ser". Verbs must own a paradigm to qualify as a base entry, which is
       what separates "hacer" from "hago".
    5. Otherwise it is an inflection: collapse onto the parent ("es" -> "ser", "tengo" -> "tener").
    """
    if word in overrides["drop"] or word in overrides["stoplist"]:
        return None
    if word in overrides["remap"]:
        target = overrides["remap"][word]
        rows = [r for r in pack.lemma_rows(target) if r[1] == target and r[2] in CONTENT_POS]
        if not rows:
            return target, "unknown"
        # Owning a paradigm is the strongest evidence of what the target really is: "ser" and
        # "haver" both have a marginal noun entry that would otherwise win on sense count alone.
        _, _, pos = max(rows, key=lambda r: (
            pack.has_paradigm(r[0]), pack.sense_count(r[0]), -pos_rank(r[2])))
        return target, pos

    lemma_rows = pack.lemma_rows(word)
    form_rows = pack.form_rows(word)
    if not lemma_rows and not form_rows:
        return None

    if any(pos in FUNCTION_POS for _, _, pos in lemma_rows):
        return None
    if any(pos in FUNCTION_POS for _, pos, _ in form_rows):
        return None

    def eligible(lemma: str) -> bool:
        """A grammar word is never a destination; otherwise "es" would land on the conjunction "e"."""
        return lemma not in overrides["drop"] and lemma not in overrides["stoplist"]

    # (3) Plural of a noun or adjective, or the feminine of an adjective. A feminine *noun* is left
    # alone on purpose: "chica" and "senora" are words a learner wants in their own right, whereas
    # "roja" is only ever the adjective "rojo" agreeing with something.
    nominal = []
    for parent, pos, tags in form_rows:
        tagset = set((tags or "").split())
        if pos not in {"noun", "adj"} or parent == word or not eligible(parent):
            continue
        if tagset & DERIVATION_TAGS or not (tagset & NOMINAL_INFLECTION_TAGS):
            continue
        if pos == "noun" and not (tagset & {"plural", "singular"}):
            continue
        nominal.append((parent, pos))
    # Parents this word could be an inflection of, reflexives last ("estar" beats "estarse").
    parents = [
        (parent, pos) for parent, pos, tags in form_rows
        if pos in CONTENT_POS and parent != word and eligible(parent)
        and not (set((tags or "").split()) & DERIVATION_TAGS)
    ]
    best_parent = None
    if parents:
        non_reflexive = [(p, t) for p, t in parents if not p.endswith("se")] or parents
        best_parent = max(non_reflexive, key=lambda lp: (support.get(lp[0], 0), -len(lp[0])))

    if nominal:
        best_nominal = max(nominal, key=lambda lp: support.get(lp[0], 0))
        # A far better-supported parent still wins. "damos" is genuinely the plural of the noun
        # "damo", but every corpus occurrence of it is "we give"; likewise "queda" is filed as the
        # feminine of "quedo" and is really the present of "quedar".
        if (best_parent and best_parent[0] != best_nominal[0]
                and support.get(best_parent[0], 0)
                >= SELF_BIAS * max(support.get(best_nominal[0], 0), 1)):
            return best_parent
        return best_nominal

    # (4) The word is its own headword. Interjections are excluded when a verb reading is available:
    # the pack tags "vamos", "quiero" and "mira" as interjections, but they are forms of ir, querer
    # and mirar and belong on those cards.
    self_rows = [
        (lid, pos) for lid, lemma, pos in lemma_rows
        if lemma == word and pos in CONTENT_POS
        and (pos != "verb" or pack.has_paradigm(lid))
        and (pos != "intj" or not parents)
    ]
    if self_rows:
        # Most-documented reading wins; POS priority breaks ties ("bien" -> adv, not noun).
        lid, pos = max(self_rows, key=lambda r: (pack.sense_count(r[0]), -pos_rank(r[1])))
        # A far better-supported parent still wins: "era" and "son" are real nouns, but essentially
        # every corpus occurrence of them is the imperfect and the present of "ser".
        if best_parent and support.get(best_parent[0], 0) >= SELF_BIAS * max(support.get(word, 0), 1):
            return best_parent
        return word, pos

    # (5) An inflection of something else.
    if best_parent:
        return best_parent

    # Nothing resolved it: keep it only if it is a content headword under some other spelling.
    content = [(lid, lemma, pos) for lid, lemma, pos in lemma_rows
               if pos in CONTENT_POS and eligible(lemma)]
    if content:
        lid, lemma, pos = max(content, key=lambda r: (pack.sense_count(r[0]), -pos_rank(r[2])))
        return lemma, pos
    return None


def build(lang: str, overrides: dict) -> list[dict]:
    """The top TOP_LEMMAS content lemmas for `lang`, ranked by rolled-up corpus count."""
    freq_path = ROOT / "data" / f"{lang}_frequency_50k.txt"
    pack_path = ROOT / "dist" / f"dict_{lang}.sqlite"
    if not freq_path.exists():
        raise SystemExit(f"no frequency list at {freq_path}; see the module docstring")
    if not pack_path.exists():
        raise SystemExit(f"no dictionary pack at {pack_path}; build it with build_dictionary.py")

    rows = read_frequency(freq_path)
    pack = Pack(pack_path)

    # Pass 1 adjudicates on raw surface counts; each later pass re-runs against the previous pass's
    # rolled-up lemma totals, so a verb scattered over forty forms finally outweighs the rare noun
    # that happens to spell one of them.
    support: dict[str, int] = dict(rows)
    totals: dict[str, int] = {}
    pos_of: dict[str, str] = {}
    members: dict[str, set[str]] = {}
    owner: dict[str, str] = {}
    dropped = 0
    for pass_no in range(1, PASSES + 1):
        totals = defaultdict(int)
        pos_of = {}
        members = defaultdict(set)
        owner = {}
        dropped = 0
        for word, count in rows:
            hit = resolve(word, pack, support, overrides)
            if hit is None:
                dropped += 1
                continue
            lemma, pos = hit
            totals[lemma] += count
            members[lemma].add(word)
            owner[word] = lemma
            # First (most frequent) surface form to reach a lemma settles its part of speech.
            pos_of.setdefault(lemma, pos)
        moved = sum(1 for k, v in totals.items() if support.get(k) != v)
        print(f"[{lang}] pass {pass_no}: {len(totals)} lemmas, {dropped} dropped, {moved} changed")
        support = dict(totals)

    ranked = sorted(totals.items(), key=lambda kv: (-kv[1], kv[0]))[:TOP_LEMMAS]
    print(f"[{lang}] {len(rows)} surface forms -> {len(totals)} lemmas "
          f"({dropped} dropped), keeping top {len(ranked)}")

    entries = []
    for i, (lemma, count) in enumerate(ranked):
        forms = set(members[lemma])
        # The lemma spells itself, so a card headworded as the lemma still matches -- but only when
        # that spelling did not resolve somewhere else. "era" is the imperfect of "ser" *and* a noun
        # meaning epoch; letting the noun claim the spelling too would put one surface form in two
        # entries and make the runtime rank lookup depend on map insertion order.
        if owner.get(lemma, lemma) == lemma:
            forms.add(lemma)
        entries.append({
            "rank": i + 1,
            "lemma": lemma,
            "pos": pos_of[lemma],
            "count": count,
            "forms": sorted(forms),
        })

    seen: dict[str, str] = {}
    for entry in entries:
        for form in entry["forms"]:
            if form in seen:
                raise SystemExit(
                    f"[{lang}] surface form {form!r} claimed by both {seen[form]!r} "
                    f"and {entry['lemma']!r}")
            seen[form] = entry["lemma"]
    return entries


def emit_tsv(lang: str, entries: list[dict]) -> str:
    header = (
        f"# Top {len(entries)} {LANGUAGES[lang]['title']} content lemmas, rolled up from "
        f"OpenSubtitles counts (hermitdave/FrequencyWords, MIT) by tools/build_frequency.py.\n"
        f"# rank\tlemma\tpos\tforms (comma-separated surface forms seen in the corpus)\n"
    )
    return header + "".join(
        f"{e['rank']}\t{e['lemma']}\t{e['pos']}\t{','.join(e['forms'])}\n" for e in entries)


def emit_kotlin(lang: str, entries: list[dict]) -> str:
    obj = LANGUAGES[lang]["object"]
    lines = [f"{e['lemma']}|{e['pos']}|{','.join(e['forms'])}" for e in entries]
    for line in lines:
        # Raw strings would break on either, and neither occurs in the source data.
        if '"' in line or "$" in line:
            raise SystemExit(f"unsafe character in generated line: {line!r}")

    blocks = [lines[i:i + LINES_PER_BLOCK] for i in range(0, len(lines), LINES_PER_BLOCK)]
    parts = []
    for i, block in enumerate(blocks):
        body = "\n".join(block)
        parts.append(f'    private const val BLOCK{i} = """\n{body}"""\n')
    names = ", ".join(f"BLOCK{i}" for i in range(len(blocks)))

    return f'''// GENERATED by tools/build_frequency.py -- do not edit by hand.
// Source: tools/{lang}_lemmas2000.tsv
package com.copiosa.domain.vocab.freq.data

/**
 * The {LANGUAGES[lang]["title"]} frequency list, packed one entry per line as
 * `lemma|pos|form,form,...`, in rank order (rank = index + 1).
 *
 * Packed rather than emitted as {len(entries)} object literals because the literal form is several
 * times larger and needs method-size chunking. The blocks are joined through [sequenceOf] rather
 * than `+` so the compiler cannot constant-fold them back into one string literal and blow the
 * 64 KB limit on a constant.
 */
internal object {obj} {{
    val lines: List<String> by lazy {{
        sequenceOf({names}).flatMap {{ it.lineSequence() }}.filter {{ it.isNotBlank() }}.toList()
    }}

{chr(10).join(parts)}}}
'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang", choices=sorted(LANGUAGES), action="append",
                        help="build only this language (repeatable); default all")
    parser.add_argument("--check-only", action="store_true", help="report, write nothing")
    args = parser.parse_args()

    for lang in args.lang or sorted(LANGUAGES):
        overrides_path = ROOT / "tools" / f"frequency_overrides_{lang}.yaml"
        raw = yaml.safe_load(overrides_path.read_text(encoding="utf-8")) if overrides_path.exists() else {}
        # YAML reads a bare `no` as False (and `on`/`off` likewise), which would silently drop the
        # Spanish word from the stoplist. The entries are quoted, and this coerces anything missed.
        for key in ("stoplist", "drop"):
            for item in raw.get(key) or []:
                if not isinstance(item, str):
                    raise SystemExit(
                        f"{overrides_path.name}: {key} entry {item!r} parsed as {type(item).__name__} "
                        f"-- quote it")
        overrides = {
            "stoplist": set(raw.get("stoplist") or []),
            "drop": set(raw.get("drop") or []),
            "remap": dict(raw.get("remap") or {}),
        }
        entries = build(lang, overrides)
        if len(entries) < TOP_LEMMAS:
            print(f"[{lang}] WARNING: only {len(entries)} lemmas, wanted {TOP_LEMMAS}")

        verify = ROOT / "tools" / "_verify" / f"frequency_{lang}.json"
        verify.parent.mkdir(parents=True, exist_ok=True)
        verify.write_text(json.dumps(entries, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[{lang}] wrote {verify.relative_to(ROOT)}")
        if args.check_only:
            continue

        tsv = ROOT / "tools" / f"{lang}_lemmas2000.tsv"
        tsv.write_text(emit_tsv(lang, entries), encoding="utf-8")
        test_copy = ROOT / "app" / "src" / "test" / "resources" / f"frequency_{lang}.tsv"
        test_copy.parent.mkdir(parents=True, exist_ok=True)
        test_copy.write_text(emit_tsv(lang, entries), encoding="utf-8")

        kotlin = (ROOT / "app/src/main/java/com/copiosa/domain/vocab/freq/data"
                  / f"FrequencyList{lang.capitalize()}Data.kt")
        kotlin.parent.mkdir(parents=True, exist_ok=True)
        kotlin.write_text(emit_kotlin(lang, entries), encoding="utf-8")
        for path in (tsv, test_copy, kotlin):
            print(f"[{lang}] wrote {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
