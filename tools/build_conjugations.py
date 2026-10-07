"""Build the conjugation drill's bundled verb lexicon.

The drill needs, for the most frequent Spanish verbs, every cell of seven paradigms plus a
classification of *how* each irregular cell deviates. Forms are sourced (hand-typing 8,400 strings is
where errors would creep in, and a wrong drilled form actively teaches the wrong thing); the
classification and the naive-form suppression are authored here, because no source carries them.

Only deviations are emitted. Regular cells are reproduced at runtime by
`domain/conjugation/Conjugator.kt`, which this script mirrors exactly -- and
`ConjugationGoldenTest` replays the emitted golden CSV through the Kotlin engine to prove the two
implementations agree on all 8,400 cells.

Inputs
    data/eswiktionary-raw.jsonl.gz   Wiktextract dump (also used by build_dictionary.py)
    data/es_frequency_50k.txt        OpenSubtitles word counts (gitignored; see --frequency)
    tools/conjugation_overrides.yaml Curated glosses, damping, restrictions and class corrections

Outputs
    app/src/main/java/com/copiosa/domain/conjugation/data/VerbLexiconData.kt
    app/src/test/resources/conjugation_golden.csv
    tools/es_top2000.tsv             Committed frequency slice, so a rebuild needs no download
    tools/_verify/conjugations.json  Human-readable dump for eyeballing

Usage
    python tools/build_conjugations.py
    python tools/build_conjugations.py --verbs 200 --check-only
"""

from __future__ import annotations

import argparse
import csv
import importlib
import gzip
import json
import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))

TOP_WORDS = 2000
DEFAULT_VERBS = 200
MIN_IRREGULAR = 40
VERBS_PER_BLOCK = 40

LANGUAGES = {"es": "conj.es", "pt": "conj.pt"}


def install_language(tag: str) -> None:
    """Adopt one language's morphology as this module's own globals.

    The pipeline below -- reading the dump, ranking by frequency, classifying deviations, emitting
    Kotlin -- is genuinely language-agnostic; only the paradigm tables, the Wiktextract tag
    signatures and the irregularity cascade differ. Rather than thread a spec object through fifteen
    functions, the chosen language module's public names are installed here, so the pipeline reads
    exactly as it did when there was only Spanish.
    """
    module = importlib.import_module(LANGUAGES[tag])
    globals().update({k: v for k, v in vars(module).items() if not k.startswith("_")})


def derived_value(verb, tense: str, person: str) -> str | None:
    """What the rules alone make of a derived cell, reading the dump's own source form."""
    src_cell, build = DERIVED[tense]
    if src_cell is None:
        return build(verb.infinitive, person)
    source = verb.forms.get(src_cell) or regular(verb.infinitive, *src_cell)
    return build(source, person) if source else None


# --------------------------------------------------------------------------------------------------
# Sources
# --------------------------------------------------------------------------------------------------

@dataclass
class Verb:
    infinitive: str
    cls: str
    forms: dict = field(default_factory=dict)          # (tense, person) -> form
    extra_forms: set = field(default_factory=set)      # every single-word surface form, for ranking
    ambiguous: list = field(default_factory=list)      # cells where the dump listed rivals


def read_frequency(path: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            parts = line.split()
            if len(parts) == 2 and parts[1].isdigit():
                counts[parts[0]] = int(parts[1])
    return counts


def read_verbs(path: Path) -> tuple[dict[str, Verb], set[str]]:
    """Every Spanish verb entry in the dump reduced to the cells we drill, plus every Spanish word
    form that some non-verb also spells."""
    verbs: dict[str, Verb] = {}
    non_verb: set[str] = set()
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            if entry.get("lang_code") != LANG:
                continue
            if entry.get("pos") != "verb":
                word = (entry.get("word") or "").strip().lower()
                if word and " " not in word:
                    non_verb.add(word)
                for item in entry.get("forms", []):
                    form = (item.get("form") or "").strip().lower()
                    if form and " " not in form:
                        non_verb.add(form)
                continue
            infinitive = entry.get("word", "")
            cls = verb_class(infinitive)
            if not cls or infinitive in verbs or " " in infinitive:
                continue
            # Old-Spanish and obsolete entries ("eser", "seer") carry full paradigms and share
            # surface forms with their modern descendants, so the frequency roll-up would otherwise
            # float them straight into the top 200.
            if ENTRY_EXCLUDE & set(entry.get("tags") or []):
                continue
            verb = Verb(infinitive=infinitive, cls=cls)
            for item in entry.get("forms", []):
                raw = (item.get("form") or "").strip()
                tags = set(item.get("tags") or [])
                if not raw or not tags or tags & FORM_EXCLUDE:
                    continue
                person = next((p for p, t in PERSON_TAGS.items() if t <= tags), None)
                if person is None:
                    if " " not in raw:
                        verb.extra_forms.add(raw.lower())
                    continue
                for tense, (need, forbid) in TENSE_TAGS.items():
                    if need <= tags and not (forbid & tags):
                        # A cell may hold more than one spelling; the language decides which is its.
                        form = clean_form(raw, tense, person)
                        if not form or " " in form:
                            break
                        verb.extra_forms.add(form.lower())
                        key = (tense, person)
                        if key in verb.forms:
                            # The dump sometimes lists a regularised rival ("sabo" beside "sé").
                            # The standard form is listed first, so keep it and record the clash.
                            # The imperfect subjunctive always has a -ra/-se pair; that is expected,
                            # not a clash, and the tense is derived at runtime anyway.
                            if verb.forms[key] != form and tense not in DERIVED:
                                chosen = prefer_rival(tense, person, verb.forms[key], form)
                                if chosen != verb.forms[key]:
                                    verb.forms[key] = chosen
                                else:
                                    verb.ambiguous.append((tense, person, verb.forms[key], form))
                        else:
                            verb.forms[key] = form
                        break
            for cell in BACKFILL_FROM_RULE:
                # Cells the dump omits because the rule already gives them — see the language
                # module. Filled from the rule, so "complete" means complete.
                if cell not in verb.forms:
                    filled = (derived_value(verb, *cell) if cell[0] in DERIVED
                              else regular(infinitive, *cell))
                    if filled:
                        verb.forms[cell] = filled
            if verb.forms:
                verbs[infinitive] = verb
    return verbs, non_verb


def apply_manual_forms(verbs: dict[str, Verb], per_verb: dict) -> list[str]:
    """Fill in cells the dump doesn't carry, from the curated `forms:` blocks.

    Not every entry has a conjugation table: estar's is empty, which silently dropped the single most
    important verb in the drill. Rather than special-case it, any verb may have its paradigm supplied
    or patched by hand.
    """
    patched = []
    for infinitive, cfg in per_verb.items():
        supplied = cfg.get("forms") or {}
        if not supplied:
            continue
        cls = verb_class(infinitive)
        if cls is None:
            raise SystemExit(f"{infinitive}: not a conjugable infinitive")
        verb = verbs.setdefault(infinitive, Verb(infinitive=infinitive, cls=cls))
        for key, form in supplied.items():
            tense_slug, person_slug = key.split(".")
            tense = next(t for t, s in TENSE_SLUG.items() if s == tense_slug)
            person = next(p for p, s in PERSON_SLUG.items() if s == person_slug)
            verb.forms[(tense, person)] = form
            verb.extra_forms.add(form.lower())
        patched.append(infinitive)
    return patched


def load_overrides(path: Path) -> dict:
    if not path.exists():
        return {"verbs": {}}
    with path.open(encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {"verbs": {}}


# --------------------------------------------------------------------------------------------------
# Selection
# --------------------------------------------------------------------------------------------------

def score_verbs(verbs: dict[str, Verb], counts: dict[str, int],
                non_verb: set[str]) -> dict[str, int]:
    """Sum the corpus count of every *unambiguously verbal* surface form onto its lemma.

    Counting every form outright lets nouns and adjectives carry dead verbs into the top 200:
    "escolar", "solar", "idear", "estrellar" all have paradigms in the dump whose high-frequency
    cells are simply the noun. Dropping any form that some non-verb also spells costs real verbs some
    count mass ("para" belongs to the preposition too) but leaves their distinctly verbal forms
    intact, while a homograph-only verb loses nearly everything.

    Forms shared between two verbs are still counted for both; that is a genuine ambiguity, and the
    damping in conjugation_overrides.yaml is the correction where it matters.
    """
    scored: dict[str, int] = {}
    for infinitive, verb in verbs.items():
        surface = set(verb.extra_forms) | {infinitive} | {f.lower() for f in verb.forms.values()}
        scored[infinitive] = sum(counts.get(f, 0) for f in surface - non_verb)
    return scored


MIN_INFINITIVE_COUNT = 100


def select(verbs: dict[str, Verb], scored: dict[str, int], counts: dict[str, int],
           want: int, exclude: set[str], incomplete: list) -> list[str]:
    ranked = sorted(scored, key=lambda v: (-scored[v], v))
    chosen = []
    for infinitive in ranked:
        verb = verbs[infinitive]
        if scored[infinitive] <= 0:
            break
        if infinitive in exclude:
            continue
        # The roll-up over surface forms is homograph-prone, so require the infinitive itself to be
        # a word people actually use. This is what keeps dead lemmas whose forms overlap a live verb
        # (eser/seer beside ser) out of the drill.
        if counts.get(infinitive, 0) < MIN_INFINITIVE_COUNT:
            continue
        missing = [(t, p) for t in TENSES for p in PERSONS
                   if t not in DERIVED and (t, p) not in verb.forms]
        if missing:
            # Reported by the caller: a frequent verb with no table in the dump is a data gap to
            # patch in conjugation_overrides.yaml, not something to drop on the floor.
            incomplete.append((infinitive, scored[infinitive], len(missing)))
            continue
        chosen.append(infinitive)
        if len(chosen) == want:
            break
    return chosen


# --------------------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------------------

@dataclass
class Built:
    infinitive: str
    rank: int
    frequency: int
    gloss: str
    cls: str
    weight: float
    restriction: dict | None
    overrides: dict          # (tense, person) -> (form, [classes], suppress)
    alternates: dict
    warnings: list


def build_verb(verb: Verb, rank: int, frequency: int, cfg: dict, warnings: list) -> Built:
    overrides: dict = {}
    forced = {tuple(k.split(".")): v for k, v in (cfg.get("classes") or {}).items()}
    suppress = {tuple(k.split(".")) for k in (cfg.get("suppress_naive") or [])}

    for tense in TENSES:
        for person in PERSONS:
            actual = verb.forms.get((tense, person))
            reg = regular(verb.infinitive, tense, person)
            if tense in DERIVED:
                # A derived tense normally costs nothing to store: it is built from its source cell
                # and inherits that cell's irregularity for free. It is written out only where the
                # dump disagrees with the derivation -- Portuguese's tivéssemos takes an acute where
                # the regular comêssemos takes a circumflex, and no rule predicts which.
                if actual is None:
                    continue
                derived_form = derived_value(verb, tense, person)
                if derived_form is None or actual == derived_form:
                    continue
            if actual is None or reg is None:
                raise SystemExit(f"missing cell {verb.infinitive} {tense}.{person}")
            if actual == reg:
                continue
            key = (TENSE_SLUG[tense], PERSON_SLUG[person])
            if key in forced:
                classes = forced[key] if isinstance(forced[key], list) else [forced[key]]
            else:
                classes = classify(verb.infinitive, verb.cls, tense, person, actual, reg, verb.forms)
                if "other_irregular" in classes and cfg.get("default_class"):
                    # A wholly irregular verb (ser, ir, haber) declares its class once rather than
                    # listing twenty near-identical per-cell corrections.
                    classes = [cfg["default_class"]]
                elif "other_irregular" in classes:
                    warnings.append(f"{verb.infinitive} {TENSE_SLUG[tense]}.{PERSON_SLUG[person]}: "
                                    f"{reg} -> {actual} unclassified")
            overrides[(tense, person)] = (actual, classes, key in suppress)


    return Built(
        infinitive=verb.infinitive, rank=rank, frequency=frequency,
        gloss=cfg.get("gloss", ""), cls=verb.cls,
        weight=float(cfg.get("weight", 1.0)),
        restriction=cfg.get("restriction"),
        overrides=overrides,
        alternates={k: v for k, v in (cfg.get("alternates") or {}).items()},
        warnings=[],
    )


def golden_rows(built: list[Built]) -> list[list[str]]:
    rows = []
    for verb in built:
        for tense in TENSES:
            for person in PERSONS:
                reg = regular(verb.infinitive, tense, person)
                override = verb.overrides.get((tense, person))
                if tense in DERIVED and override is None:
                    # Mirrors ConjugationEngine: an unstored derived cell reads its source cell,
                    # taking that cell's irregularity and its naive-suppression with it.
                    src_cell, build = DERIVED[tense]
                    src_override = verb.overrides.get(src_cell) if src_cell else None
                    source = (src_override[0] if src_override
                              else (regular(verb.infinitive, *src_cell) if src_cell
                                    else verb.infinitive))
                    form = build(source, person) if source else None
                    classes = list(src_override[1]) if src_override else []
                    suppressed = bool(src_override and src_override[2])
                else:
                    form = override[0] if override else reg
                    classes = list(override[1]) if override else []
                    suppressed = bool(override and override[2])
                shows = (form != reg and not suppressed
                         and not any(c in SUPPLETIVE_CLASSES for c in classes)
                         and len(stem_of(verb.infinitive)) >= 2)
                rows.append([verb.infinitive, TENSE_SLUG[tense], PERSON_SLUG[person],
                             form, reg, "1" if shows else "0", " ".join(classes)])
    return rows


KOTLIN_HEADER = '''package com.copiosa.domain.conjugation.data

import com.copiosa.domain.conjugation.Cell
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.DrillRestriction
import com.copiosa.domain.conjugation.FormOverride
import com.copiosa.domain.conjugation.IrregularityClass
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.conjugation.VerbClass
import com.copiosa.domain.conjugation.VerbEntry

/**
 * GENERATED by `python tools/build_conjugations.py --lang {lang}` -- do not edit by hand.
 *
 * The {n} most frequent {title} verbs. Only cells that deviate from the rules are stored;
 * everything else is produced at runtime by Conjugator, and a derived paradigm is built from its
 * source cell rather than stored -- except where the source can't predict it.
 *
 * Verified against {verify} by the golden replay test.
 */
internal object {obj} {{

    val entries: List<VerbEntry> by lazy {{ {blocks} }}
'''


# Slugs whose Kotlin constant isn't just the slug uppercased.
KOTLIN_ENUM_ALIASES = {
    "other_irregular": "OTHER",
    "pt_eu_c": "PT_EU_C_CEDILLA",
    "pt_other_irregular": "PT_OTHER",
}


def kotlin_enum(class_slug: str) -> str:
    """The IrregularityClass constant for a slug. Names mirror slugs, so this is mechanical; the
    aliases above are the handful that were named before the convention settled."""
    return KOTLIN_ENUM_ALIASES.get(class_slug, class_slug.upper())


def emit_kotlin(built: list[Built]) -> str:
    blocks = [built[i:i + VERBS_PER_BLOCK] for i in range(0, len(built), VERBS_PER_BLOCK)]
    names = " + ".join(f"block{i}()" for i in range(len(blocks)))
    out = [KOTLIN_HEADER.format(
        n=len(built), blocks=names, lang=LANG, title=KOTLIN_TITLE, obj=KOTLIN_OBJECT,
        verify=VERIFY_OUT.relative_to(ROOT).as_posix(),
    )]
    for i, block in enumerate(blocks):
        out.append(f"\n    private fun block{i}(): List<VerbEntry> = listOf(")
        for verb in block:
            out.append(emit_verb(verb))
        out.append("    )\n")
    out.append("}\n")
    return "".join(out)


def emit_verb(verb: Built) -> str:
    lines = ["        VerbEntry("]
    lines.append(f'            infinitive = "{verb.infinitive}", rank = {verb.rank}, '
                 f"frequency = {verb.frequency}.0,")
    lines.append(f'            gloss = "{verb.gloss}", verbClass = VerbClass.{verb.cls},')
    if verb.weight != 1.0:
        lines.append(f"            weightMultiplier = {verb.weight},")
    if verb.restriction:
        parts = []
        if verb.restriction.get("directions"):
            joined = ", ".join(f"DrillDirection.{d.upper()}" for d in verb.restriction["directions"])
            parts.append(f"allowedDirections = setOf({joined})")
        if verb.restriction.get("tenses"):
            joined = ", ".join(f"Tense.{t}" for t in verb.restriction["tenses"])
            parts.append(f"allowedTenses = setOf({joined})")
        if verb.restriction.get("persons"):
            joined = ", ".join(f"Person.{p}" for p in verb.restriction["persons"])
            parts.append(f"allowedPersons = setOf({joined})")
        lines.append(f"            restriction = DrillRestriction({', '.join(parts)}),")
    if verb.overrides:
        lines.append("            overrides = mapOf(")
        by_tense: dict[str, list] = defaultdict(list)
        for (tense, person), value in verb.overrides.items():
            by_tense[tense].append((person, value))
        for tense in TENSES:
            if tense not in by_tense:
                continue
            lines.append(f"                Tense.{tense} to mapOf(")
            for person, (form, classes, suppress) in sorted(
                    by_tense[tense], key=lambda kv: PERSONS.index(kv[0])):
                joined = ", ".join(f"IrregularityClass.{kotlin_enum(c)}" for c in classes)
                extra = ", suppressNaive = true" if suppress else ""
                lines.append(f'                    Person.{person} to '
                             f'FormOverride("{form}", listOf({joined}){extra}),')
            lines.append("                ),")
        lines.append("            ),")
    if verb.alternates:
        lines.append("            alternateForms = mapOf(")
        for key, form in verb.alternates.items():
            tense_slug, person_slug = key.split(".")
            tense = next(t for t, s in TENSE_SLUG.items() if s == tense_slug)
            person = next(p for p, s in PERSON_SLUG.items() if s == person_slug)
            lines.append(f'                Cell(Tense.{tense}, Person.{person}) to "{form}",')
        lines.append("            ),")
    lines.append("        ),")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--lang", default="es", choices=sorted(LANGUAGES),
                    help="Which language's morphology to build (default: es).")
    ap.add_argument("--verbs", type=int, default=DEFAULT_VERBS)
    ap.add_argument("--frequency", type=Path, default=None,
                    help="Frequency list; defaults to the raw download, else the committed slice.")
    ap.add_argument("--check-only", action="store_true", help="Report only; write nothing.")
    args = ap.parse_args()
    install_language(args.lang)

    freq_path = args.frequency or (FREQ_RAW if FREQ_RAW.exists() else FREQ_SLICE)
    if not freq_path.exists():
        raise SystemExit(f"no frequency list at {freq_path}; see the module docstring")
    if not WIKT.exists():
        raise SystemExit(f"no Wiktextract dump at {WIKT}")

    print(f"frequency:  {freq_path.relative_to(ROOT)}")
    counts = read_frequency(freq_path)
    print(f"            {len(counts)} words")

    print("wiktionary: scanning...")
    verbs, non_verb = read_verbs(WIKT)
    print(f"            {len(verbs)} {LANG} verbs with forms, "
          f"{len(non_verb)} word forms also spelled by a non-verb")

    cfg = load_overrides(OVERRIDES)
    per_verb = cfg.get("verbs") or {}
    patched = apply_manual_forms(verbs, per_verb)
    if patched:
        print(f"            {len(patched)} paradigm(s) supplied by hand: {' '.join(patched)}")

    scored = score_verbs(verbs, counts, non_verb)
    # Glosses live in a flat map so 200 one-liners don't need 200 nested blocks; a per-verb block
    # may still carry its own and wins.
    for infinitive, gloss in (cfg.get("glosses") or {}).items():
        per_verb.setdefault(infinitive, {}).setdefault("gloss", gloss)
    excluded = set(cfg.get("exclude") or [])
    incomplete: list = []
    chosen = select(verbs, scored, counts, args.verbs, excluded, incomplete)
    if len(chosen) < args.verbs:
        raise SystemExit(f"only {len(chosen)} complete verbs available, wanted {args.verbs}")

    # A verb frequent enough to belong in the drill but missing a table is a dump gap, not a
    # judgement call -- estar's entry carries no forms at all.
    cutoff = scored[chosen[-1]]
    gaps = [g for g in incomplete if g[1] >= cutoff and g[0] not in excluded]
    if gaps:
        print(f"\n{len(gaps)} frequent verb(s) dropped for an incomplete paradigm -- "
              f"supply `forms:` in {OVERRIDES.name}:")
        for infinitive, score, n_missing in sorted(gaps, key=lambda g: -g[1])[:20]:
            print(f"  # {infinitive} (freq {score}, {n_missing} cells missing)")

    warnings: list[str] = []
    built = [build_verb(verbs[inf], rank + 1, scored[inf], per_verb.get(inf, {}), warnings)
             for rank, inf in enumerate(chosen)]

    irregular = [b for b in built if b.overrides]
    print(f"selected:   {len(built)} verbs, {len(irregular)} irregular, "
          f"{sum(len(b.overrides) for b in built)} override cells")
    if len(irregular) < MIN_IRREGULAR:
        raise SystemExit(f"only {len(irregular)} irregular verbs, need at least {MIN_IRREGULAR}")

    missing_gloss = [b.infinitive for b in built if not b.gloss]
    ambiguous = [(v, verbs[v].ambiguous) for v in chosen if verbs[v].ambiguous]
    if warnings:
        print(f"\n{len(warnings)} unclassified cells -- adjudicate into {OVERRIDES.name}:")
        for line in warnings[:60]:
            print(f"  ! {line}")
        if len(warnings) > 60:
            print(f"  ... and {len(warnings) - 60} more")
    if ambiguous:
        print(f"\n{len(ambiguous)} verbs with rival forms in the dump (first kept):")
        for infinitive, clashes in ambiguous[:20]:
            for tense, person, kept, dropped in clashes[:3]:
                print(f"  ? {infinitive} {TENSE_SLUG[tense]}.{PERSON_SLUG[person]}: "
                      f"kept {kept}, dropped {dropped}")
    if missing_gloss:
        print(f"\n{len(missing_gloss)} verbs without a gloss -- add to {OVERRIDES.name}:")
        print("  " + " ".join(missing_gloss))

    if args.check_only:
        return 0

    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_OUT.write_text(emit_kotlin(built), encoding="utf-8")
    print(f"\nwrote {KOTLIN_OUT.relative_to(ROOT)}")

    GOLDEN_OUT.parent.mkdir(parents=True, exist_ok=True)
    with GOLDEN_OUT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["infinitive", "tense", "person", "form", "naive", "showsNaive", "classes"])
        writer.writerows(golden_rows(built))
    print(f"wrote {GOLDEN_OUT.relative_to(ROOT)} ({len(built) * len(TENSES) * len(PERSONS)} cells)")

    top = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:TOP_WORDS]
    FREQ_SLICE.write_text(
        "# Top %d Spanish words by OpenSubtitles frequency (hermitdave/FrequencyWords, MIT).\n"
        "# Committed so tools/build_conjugations.py runs without the full 50k download.\n" % TOP_WORDS
        + "".join(f"{w}\t{c}\n" for w, c in top), encoding="utf-8")
    print(f"wrote {FREQ_SLICE.relative_to(ROOT)}")

    VERIFY_OUT.parent.mkdir(parents=True, exist_ok=True)
    VERIFY_OUT.write_text(json.dumps({
        "verbs": len(built), "irregular": len(irregular),
        "unclassified": warnings, "missingGloss": missing_gloss,
        "entries": [{
            "infinitive": b.infinitive, "rank": b.rank, "frequency": b.frequency,
            "gloss": b.gloss, "weight": b.weight,
            "overrides": {f"{TENSE_SLUG[t]}.{PERSON_SLUG[p]}": {"form": v[0], "classes": v[1]}
                          for (t, p), v in b.overrides.items()},
        } for b in built],
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {VERIFY_OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
