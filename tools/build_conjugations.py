"""Build the conjugation drill's bundled verb lexicon.

The drill needs, for the most frequent Spanish verbs, every cell of seven paradigms plus a
classification of *how* each irregular cell deviates. Forms are sourced (hand-typing 8,400 strings is
where errors would creep in, and a wrong drilled form actively teaches the wrong thing); the
classification and the naive-form suppression are authored here, because no source carries them.

Only deviations are emitted. Regular cells are reproduced at runtime by
`domain/conjugation/RegularConjugator.kt`, which this script mirrors exactly -- and
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
WIKT = ROOT / "data" / "eswiktionary-raw.jsonl.gz"
FREQ_RAW = ROOT / "data" / "es_frequency_50k.txt"
FREQ_SLICE = ROOT / "tools" / "es_top2000.tsv"
OVERRIDES = ROOT / "tools" / "conjugation_overrides.yaml"
KOTLIN_OUT = ROOT / "app/src/main/java/com/copiosa/domain/conjugation/data/VerbLexiconData.kt"
GOLDEN_OUT = ROOT / "app/src/test/resources/conjugation_golden.csv"
VERIFY_OUT = ROOT / "tools" / "_verify" / "conjugations.json"

TOP_WORDS = 2000
DEFAULT_VERBS = 200
MIN_IRREGULAR = 40
VERBS_PER_BLOCK = 40

# --------------------------------------------------------------------------------------------------
# Paradigm model -- mirrors domain/conjugation/Paradigm.kt
# --------------------------------------------------------------------------------------------------

TENSES = ["PRESENT", "PRETERITE", "IMPERFECT", "FUTURE", "CONDITIONAL",
          "PRESENT_SUBJUNCTIVE", "IMPERFECT_SUBJUNCTIVE"]
TENSE_SLUG = {"PRESENT": "pres", "PRETERITE": "pret", "IMPERFECT": "imp", "FUTURE": "fut",
              "CONDITIONAL": "cond", "PRESENT_SUBJUNCTIVE": "sub_pres",
              "IMPERFECT_SUBJUNCTIVE": "sub_imp"}
PERSONS = ["YO", "TU", "EL", "NOSOTROS", "VOSOTROS", "ELLOS"]
PERSON_SLUG = {"YO": "1s", "TU": "2s", "EL": "3s", "NOSOTROS": "1p", "VOSOTROS": "2p", "ELLOS": "3p"}

# Wiktextract tag signatures. Spanish Wiktionary calls the simple preterite
# "pretérito perfecto simple", which surfaces as indicative+present+perfect -- not a compound tense.
TENSE_TAGS = {
    "PRESENT": ({"indicative", "present"}, {"perfect", "compound", "progressive"}),
    "PRETERITE": ({"indicative", "present", "perfect"}, {"compound", "progressive"}),
    "IMPERFECT": ({"indicative", "past", "imperfect"}, {"compound", "progressive"}),
    "FUTURE": ({"indicative", "future"}, {"perfect", "compound", "progressive"}),
    "CONDITIONAL": ({"conditional"}, {"perfect", "compound", "progressive"}),
    "PRESENT_SUBJUNCTIVE": ({"subjunctive", "present"}, {"perfect", "compound", "progressive"}),
    "IMPERFECT_SUBJUNCTIVE": ({"subjunctive", "past", "imperfect"},
                              {"perfect", "compound", "progressive", "pluperfect"}),
}
PERSON_TAGS = {
    "YO": {"first-person", "singular"}, "TU": {"second-person", "singular"},
    "EL": {"third-person", "singular"}, "NOSOTROS": {"first-person", "plural"},
    "VOSOTROS": {"second-person", "plural"}, "ELLOS": {"third-person", "plural"},
}
# "vos-form" is a distinct pronoun we don't drill; the rest are tenses/registers out of scope.
FORM_EXCLUDE = {"vos-form", "archaic", "obsolete", "dated", "impersonal", "imperative",
                "anterior", "pluperfect", "participle", "gerund", "infinitive"}
ENTRY_EXCLUDE = {"archaic", "obsolete", "dated", "rare", "misspelling"}

VOWELS = "aeiouáéíóúü"
PLAIN = {"á": "a", "é": "e", "í": "i", "ó": "o", "ú": "u", "ü": "u"}
ACCENT = {"a": "á", "e": "é", "i": "í", "o": "ó", "u": "ú"}

PRESENT_AR = ["o", "as", "a", "amos", "áis", "an"]
PRESENT_ER = ["o", "es", "e", "emos", "éis", "en"]
PRESENT_IR = ["o", "es", "e", "imos", "ís", "en"]
PRETERITE_AR = ["é", "aste", "ó", "amos", "asteis", "aron"]
PRETERITE_ER_IR = ["í", "iste", "ió", "imos", "isteis", "ieron"]
IMPERFECT_AR = ["aba", "abas", "aba", "ábamos", "abais", "aban"]
IMPERFECT_ER_IR = ["ía", "ías", "ía", "íamos", "íais", "ían"]
FUTURE = ["é", "ás", "á", "emos", "éis", "án"]
CONDITIONAL = ["ía", "ías", "ía", "íamos", "íais", "ían"]
SUBJ_AR = ["e", "es", "e", "emos", "éis", "en"]
SUBJ_ER_IR = ["a", "as", "a", "amos", "áis", "an"]
IMPERFECT_SUBJ = ["ra", "ras", "ra", "ramos", "rais", "ran"]
STRONG_PRET_ENDINGS = ["e", "iste", "o", "imos", "isteis", "ieron"]


def verb_class(infinitive: str) -> str | None:
    if len(infinitive) < 2:
        return None
    tail = infinitive[-2:]
    return {"ar": "AR", "ár": "AR", "er": "ER", "ér": "ER", "ir": "IR", "ír": "IR"}.get(tail)


def stem_of(infinitive: str) -> str:
    return infinitive[:-2]


def ending(cls: str, tense: str, person: str) -> str:
    i = PERSONS.index(person)
    if tense == "PRESENT":
        return {"AR": PRESENT_AR, "ER": PRESENT_ER, "IR": PRESENT_IR}[cls][i]
    if tense == "PRETERITE":
        return (PRETERITE_AR if cls == "AR" else PRETERITE_ER_IR)[i]
    if tense == "IMPERFECT":
        return (IMPERFECT_AR if cls == "AR" else IMPERFECT_ER_IR)[i]
    if tense == "FUTURE":
        return FUTURE[i]
    if tense == "CONDITIONAL":
        return CONDITIONAL[i]
    if tense == "PRESENT_SUBJUNCTIVE":
        return (SUBJ_AR if cls == "AR" else SUBJ_ER_IR)[i]
    return IMPERFECT_SUBJ[i]


def accent_final_vowel(text: str) -> str:
    for i in range(len(text) - 1, -1, -1):
        if text[i] in "aeiou":
            return text[:i] + ACCENT[text[i]] + text[i + 1:]
    return text


def imperfect_subjunctive(preterite_3p: str, person: str) -> str | None:
    if not preterite_3p.endswith("ron"):
        return None
    base = preterite_3p[:-3]
    if not base:
        return None
    if person == "NOSOTROS":
        base = accent_final_vowel(base)
    return base + IMPERFECT_SUBJ[PERSONS.index(person)]


def regular(infinitive: str, tense: str, person: str) -> str | None:
    cls = verb_class(infinitive)
    if cls is None:
        return None
    if tense == "IMPERFECT_SUBJUNCTIVE":
        p3 = regular(infinitive, "PRETERITE", "ELLOS")
        return imperfect_subjunctive(p3, person) if p3 else None
    base = infinitive if tense in ("FUTURE", "CONDITIONAL") else stem_of(infinitive)
    return base + ending(cls, tense, person)


# --------------------------------------------------------------------------------------------------
# Classification
# --------------------------------------------------------------------------------------------------

STEM_VOWEL_CHANGES = {
    ("e", "ie"): "stem_e_ie", ("o", "ue"): "stem_o_ue", ("e", "i"): "stem_e_i",
    ("u", "ue"): "stem_u_ue", ("i", "ie"): "stem_i_ie",
}
PRETERITE_RAISES = {("e", "i"): "stem_raise_pret", ("o", "u"): "stem_raise_pret"}


def strip_accents(text: str) -> str:
    return "".join(PLAIN.get(c, c) for c in text)


def is_nucleus(text: str, i: int) -> bool:
    """Whether text[i] is a vowel of the syllable nucleus.

    The u of "qu" and "gu" before e/i is orthography, not a sound -- treating it as a vowel makes
    querer's stem nucleus read "ue" instead of "e", which hides a plain e->ie stem change.
    """
    c = text[i]
    if c not in VOWELS:
        return False
    if c == "u" and i > 0:
        prev = text[i - 1]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if prev == "q":
            return False
        if prev == "g" and nxt in ("e", "i", "é", "í"):
            return False
    return True


def last_vowel_run(text: str) -> tuple[int, int, str]:
    """Index range and text of the final maximal nucleus run -- the stressed nucleus of a stem."""
    end = len(text)
    while end > 0 and not is_nucleus(text, end - 1):
        end -= 1
    start = end
    while start > 0 and is_nucleus(text, start - 1):
        start -= 1
    return start, end, text[start:end]


def consonant_skeleton(text: str) -> str:
    plain = strip_accents(text)
    return "".join(c for i, c in enumerate(plain) if not is_nucleus(plain, i))


def stem_change_class(reg_stem: str, act_stem: str, tense: str, person: str) -> str | None:
    """A stem change keeps the consonant skeleton and rewrites the stressed vowel nucleus."""
    if consonant_skeleton(reg_stem) != consonant_skeleton(act_stem):
        return None
    old = strip_accents(last_vowel_run(reg_stem)[2])
    new = strip_accents(last_vowel_run(act_stem)[2])
    if not old or old == new:
        return None
    if tense == "PRETERITE" and person in ("EL", "ELLOS"):
        raised = PRETERITE_RAISES.get((old, new))
        if raised:
            return raised
    return STEM_VOWEL_CHANGES.get((old, new))


def split_regular(form: str, cls: str, tense: str, person: str) -> tuple[str, str] | None:
    """Split a form into (stem, ending) when it carries the ending the rules predict."""
    end = ending(cls, tense, person)
    if form.endswith(end) and len(form) > len(end):
        return form[: -len(end)], end
    return None


def classify(infinitive: str, cls: str, tense: str, person: str,
             actual: str, reg: str, forms: dict) -> list[str]:
    """The class(es) of a single deviating cell. Returns ["other_irregular"] when nothing fits."""
    idx = PERSONS.index(person)
    reg_split = split_regular(reg, cls, tense, person)
    reg_stem = reg_split[0] if reg_split else stem_of(infinitive)
    act_split = split_regular(actual, cls, tense, person)

    # Written accent only: vi/ví, creíste/creiste, estás/estas. Checked before anything
    # tense-specific, because it can happen in any paradigm.
    if strip_accents(actual) == strip_accents(reg):
        return ["accent_shift"]

    # -uir verbs wedge a y between stem and ending (construyes, huye, destruyen) instead of
    # touching the stem vowel, so no stem-change rule will ever see it.
    if act_split and reg_split and act_split[0] == reg_split[0] + "y":
        return ["orth_y_hiatus"]

    if tense in ("FUTURE", "CONDITIONAL"):
        end = ending(cls, tense, person)
        if not actual.endswith(end):
            return ["other_irregular"]
        base = actual[: -len(end)]
        if base.endswith("dr"):
            return ["fut_dr"]
        if base == stem_of(infinitive) + "r":
            return ["fut_drop"]
        return ["fut_irregular"]

    if tense == "IMPERFECT":
        return ["imperfect_irregular"]

    if tense == "PRETERITE":
        reg_end = ending(cls, tense, person)
        # A strong preterite pulls the stress onto the stem and takes unstressed endings. The j-stems
        # additionally swallow the i of -ieron (dijeron, trajeron -- never "dijieron").
        #
        # Gate the whole branch on the verb *having* a strong preterite, which only its first-person
        # singular reveals: unstressed -e (tuve, puse, supe) rather than -é / -í. Without this, any
        # third-person stem raise carrying a regular ending -- durmieron, siguieron, destruyeron --
        # gets read as a strong stem, which files it under the wrong pattern entirely.
        candidates = [STRONG_PRET_ENDINGS[idx]] + (["eron"] if person == "ELLOS" else [])
        yo = forms.get(("PRETERITE", "YO"), "")
        if not (yo.endswith("e") and not yo.endswith("é")):
            candidates = []
        for strong_end in candidates:
            if not actual.endswith(strong_end):
                continue
            strong_stem = actual[: -len(strong_end)]
            if strong_stem == reg_stem:
                continue  # the regular ending happens to match; nothing strong about it
            shape = strong_stem_class(strong_stem)
            if shape == "other_irregular":
                continue
            classes = [shape]
            if person in ("YO", "EL") and not actual.endswith(reg_end):
                classes.append("strong_pret_ending")
            return classes
        if act_split:
            changed = stem_change_class(reg_stem, act_split[0], tense, person)
            if changed:
                return [changed]
        if person == "YO" and re.search(r"(qué|gué|cé)$", actual):
            return ["orth_car_gar_zar"]
        if person in ("EL", "ELLOS") and "y" in actual and "y" not in reg:
            return ["orth_y_hiatus"]
        # A dropped unstressed i, and nothing else: bullir's "bulleron" for "bullieron". Matching the
        # exact string keeps this off the j-stems, which are shorter than the regular form too.
        if reg_end.startswith("i") and actual == reg_stem + reg_end[1:]:
            return ["orth_i_loss"]
        return ["other_irregular"]

    if tense == "PRESENT_SUBJUNCTIVE":
        # The present subjunctive is built off the yo form; when that is irregular, so is this.
        yo_present = forms.get(("PRESENT", "YO"))
        if yo_present and yo_present.endswith("o") and len(yo_present) > 1:
            yo_stem = yo_present[:-1]
            if act_split and act_split[0] == yo_stem and yo_stem != reg_stem:
                return ["subj_yo_stem"]
        if act_split:
            changed = stem_change_class(reg_stem, act_split[0], tense, person)
            if changed:
                return [changed]
        return ["subj_irregular"]

    # PRESENT
    if person == "YO":
        if actual.endswith("zco"):
            return ["yo_zco"]
        if actual.endswith("oy"):
            return ["yo_oy"]
        if actual.endswith("go"):
            return ["yo_go"]
        if actual.endswith("jo") and reg.endswith(("go", "guo")):
            return ["orth_ger_gir_guir"]
    if act_split:
        changed = stem_change_class(reg_stem, act_split[0], tense, person)
        if changed:
            return [changed]
    if person == "YO":
        return ["yo_other"]
    return ["other_irregular"]


def strong_stem_class(strong_stem: str) -> str:
    """Which shape of strong preterite stem this is. The j-stems are checked first: "dij" and "traj"
    both contain an i, so a vowel test alone would misfile them."""
    if strong_stem.endswith("j"):
        return "strong_pret_j"
    _, _, nucleus = last_vowel_run(strong_stem)
    nucleus = strip_accents(nucleus)
    if "u" in nucleus:
        return "strong_pret_u"
    if "i" in nucleus:
        return "strong_pret_i"
    return "other_irregular"


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
            if entry.get("lang_code") != "es":
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
                form = (item.get("form") or "").strip()
                tags = set(item.get("tags") or [])
                if not form or " " in form or not tags or tags & FORM_EXCLUDE:
                    continue
                verb.extra_forms.add(form.lower())
                person = next((p for p, t in PERSON_TAGS.items() if t <= tags), None)
                if person is None:
                    continue
                for tense, (need, forbid) in TENSE_TAGS.items():
                    if need <= tags and not (forbid & tags):
                        key = (tense, person)
                        if key in verb.forms:
                            # The dump sometimes lists a regularised rival ("sabo" beside "sé").
                            # The standard form is listed first, so keep it and record the clash.
                            # The imperfect subjunctive always has a -ra/-se pair; that is expected,
                            # not a clash, and the tense is derived at runtime anyway.
                            if verb.forms[key] != form and tense != "IMPERFECT_SUBJUNCTIVE":
                                verb.ambiguous.append((tense, person, verb.forms[key], form))
                        else:
                            verb.forms[key] = form
                        break
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
                   if t != "IMPERFECT_SUBJUNCTIVE" and (t, p) not in verb.forms]
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
        if tense == "IMPERFECT_SUBJUNCTIVE":
            continue  # derived at runtime from the third-plural preterite
        for person in PERSONS:
            actual = verb.forms.get((tense, person))
            reg = regular(verb.infinitive, tense, person)
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

    # The imperfect subjunctive must never be stored: it is a rule applied to the preterite.
    if any(k.startswith("sub_imp") for k in (cfg.get("classes") or {})):
        raise SystemExit(f"{verb.infinitive}: sub_imp is derived and cannot be overridden")

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
        pret3p = verb.overrides.get(("PRETERITE", "ELLOS"))
        for tense in TENSES:
            for person in PERSONS:
                reg = regular(verb.infinitive, tense, person)
                if tense == "IMPERFECT_SUBJUNCTIVE":
                    source = pret3p[0] if pret3p else regular(verb.infinitive, "PRETERITE", "ELLOS")
                    form = imperfect_subjunctive(source, person)
                    classes = list(pret3p[1]) if pret3p else []
                    suppressed = bool(pret3p and pret3p[2])
                else:
                    override = verb.overrides.get((tense, person))
                    form = override[0] if override else reg
                    classes = list(override[1]) if override else []
                    suppressed = bool(override and override[2])
                shows = (form != reg and not suppressed
                         and not any(c in SUPPLETIVE_CLASSES for c in classes)
                         and len(stem_of(verb.infinitive)) >= 2)
                rows.append([verb.infinitive, TENSE_SLUG[tense], PERSON_SLUG[person],
                             form, reg, "1" if shows else "0", " ".join(classes)])
    return rows


SUPPLETIVE_CLASSES = {"suppletive", "imperfect_irregular", "subj_irregular", "other_irregular"}

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
 * GENERATED by tools/build_conjugations.py -- do not edit by hand.
 *
 * The {n} most frequent Spanish verbs. Only cells that deviate from RegularConjugator are stored;
 * everything else is produced by rule at runtime, and the imperfect subjunctive is derived from the
 * third-person plural preterite rather than stored at all.
 *
 * Verified against tools/_verify/conjugations.json by ConjugationGoldenTest.
 */
internal object VerbLexiconData {{

    val entries: List<VerbEntry> by lazy {{ {blocks} }}
'''


def kotlin_enum(class_slug: str) -> str:
    return {
        "stem_e_ie": "STEM_E_IE", "stem_o_ue": "STEM_O_UE", "stem_e_i": "STEM_E_I",
        "stem_u_ue": "STEM_U_UE", "stem_i_ie": "STEM_I_IE", "stem_raise_pret": "STEM_RAISE_PRET",
        "yo_go": "YO_GO", "yo_zco": "YO_ZCO", "yo_oy": "YO_OY", "yo_other": "YO_OTHER",
        "subj_yo_stem": "SUBJ_YO_STEM", "strong_pret_u": "STRONG_PRET_U",
        "strong_pret_i": "STRONG_PRET_I", "strong_pret_j": "STRONG_PRET_J",
        "strong_pret_ending": "STRONG_PRET_ENDING", "fut_dr": "FUT_DR", "fut_drop": "FUT_DROP",
        "fut_irregular": "FUT_IRREGULAR", "orth_car_gar_zar": "ORTH_CAR_GAR_ZAR",
        "orth_ger_gir_guir": "ORTH_GER_GIR_GUIR", "orth_y_hiatus": "ORTH_Y_HIATUS",
        "orth_i_loss": "ORTH_I_LOSS", "accent_shift": "ACCENT_SHIFT", "suppletive": "SUPPLETIVE",
        "imperfect_irregular": "IMPERFECT_IRREGULAR", "subj_irregular": "SUBJ_IRREGULAR",
        "other_irregular": "OTHER",
    }[class_slug]


def emit_kotlin(built: list[Built]) -> str:
    blocks = [built[i:i + VERBS_PER_BLOCK] for i in range(0, len(built), VERBS_PER_BLOCK)]
    names = " + ".join(f"block{i}()" for i in range(len(blocks)))
    out = [KOTLIN_HEADER.format(n=len(built), blocks=names)]
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
    ap.add_argument("--verbs", type=int, default=DEFAULT_VERBS)
    ap.add_argument("--frequency", type=Path, default=None,
                    help="Frequency list; defaults to the raw download, else the committed slice.")
    ap.add_argument("--check-only", action="store_true", help="Report only; write nothing.")
    args = ap.parse_args()

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
    print(f"            {len(verbs)} Spanish verbs with forms, "
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
    print(f"wrote {GOLDEN_OUT.relative_to(ROOT)} ({len(built) * 42} cells)")

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
