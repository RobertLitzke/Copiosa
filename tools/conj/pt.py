"""European Portuguese verb morphology for the conjugation generator.

Mirrors `domain/conjugation/PortugueseConjugation.kt` and `Conjugator.kt` exactly; the golden CSV
this produces is replayed through the Kotlin engine by `ConjugationGoldenPtTest`, so a divergence
between the two is a test failure rather than a silently wrong drill.

Two paradigms have no Spanish counterpart. The **future subjunctive** (*se fizer*) is built off the
third-plural preterite, exactly as the imperfect subjunctive is, and the **personal infinitive**
(*para fazermos*) is built off the infinitive. For a regular verb they are spelled identically; for
an irregular one they diverge, which is the distinction the drill exists to teach.

This is pt-PT: the first-person plural preterite is written *falámos*, and *tu* is a core person.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

LANG = "pt"
WIKT = ROOT / "data" / "ptwiktionary-raw.jsonl.gz"
FREQ_RAW = ROOT / "data" / "pt_frequency_50k.txt"
FREQ_SLICE = ROOT / "tools" / "pt_top2000.tsv"
OVERRIDES = ROOT / "tools" / "conjugation_overrides_pt.yaml"
KOTLIN_OUT = ROOT / "app/src/main/java/com/copiosa/domain/conjugation/data/VerbLexiconDataPt.kt"
GOLDEN_OUT = ROOT / "app/src/test/resources/conjugation_golden_pt.csv"
VERIFY_OUT = ROOT / "tools" / "_verify" / "conjugations_pt.json"
KOTLIN_OBJECT = "VerbLexiconDataPt"
KOTLIN_TITLE = "European Portuguese"

# --------------------------------------------------------------------------------------------------
# Paradigm model -- mirrors domain/conjugation/Paradigm.kt and PortugueseConjugation.kt
# --------------------------------------------------------------------------------------------------

TENSES = ["PRESENT", "PRETERITE", "IMPERFECT", "FUTURE", "CONDITIONAL",
          "PRESENT_SUBJUNCTIVE", "IMPERFECT_SUBJUNCTIVE", "FUTURE_SUBJUNCTIVE",
          "PERSONAL_INFINITIVE"]
TENSE_SLUG = {"PRESENT": "pres", "PRETERITE": "pret", "IMPERFECT": "imp", "FUTURE": "fut",
              "CONDITIONAL": "cond", "PRESENT_SUBJUNCTIVE": "sub_pres",
              "IMPERFECT_SUBJUNCTIVE": "sub_imp", "FUTURE_SUBJUNCTIVE": "sub_fut",
              "PERSONAL_INFINITIVE": "inf_pess"}
# The enum constant names stay Spanish (they are frozen in Kotlin); the pronouns they render as are
# eu/tu/ele/nós/vós/eles, supplied by the ConjugationSpec.
PERSONS = ["YO", "TU", "EL", "NOSOTROS", "VOSOTROS", "ELLOS"]
PERSON_SLUG = {"YO": "1s", "TU": "2s", "EL": "3s", "NOSOTROS": "1p", "VOSOTROS": "2p", "ELLOS": "3p"}

# Wiktextract tag signatures for the Portuguese-edition dump. Portuguese Wiktionary names the simple
# preterite "pretérito perfeito", which surfaces as indicative+past; the "futuro do pretérito" is what
# the app calls the conditional, and the conjuntivo tables carry both "subjunctive" and "conjunctive".
# Wiktextract tag signatures for the Portuguese-edition dump, read off its actual vocabulary rather
# than guessed: the pretérito imperfeito is tagged "continuative" (not "imperfect"), the futuro do
# pretérito -- which the app calls the conditional -- is indicative+past+future, and the conjuntivo
# tables carry "subjunctive" and "conjunctive" together.
TENSE_TAGS = {
    "PRESENT": ({"indicative", "present"},
                {"future", "past", "perfect", "compound", "progressive", "subjunctive",
                 "pluperfect", "continuative"}),
    "PRETERITE": ({"indicative", "past"},
                  {"continuative", "future", "imperfect", "perfect", "compound", "progressive",
                   "subjunctive", "pluperfect"}),
    "IMPERFECT": ({"indicative", "past", "continuative"},
                  {"future", "perfect", "compound", "progressive", "subjunctive", "pluperfect"}),
    "FUTURE": ({"indicative", "future", "present"},
               {"past", "perfect", "compound", "progressive", "subjunctive", "pluperfect"}),
    "CONDITIONAL": ({"indicative", "future", "past"},
                    {"present", "perfect", "compound", "progressive", "subjunctive",
                     "pluperfect", "continuative"}),
    "PRESENT_SUBJUNCTIVE": ({"subjunctive", "present"},
                            {"future", "past", "perfect", "compound", "progressive",
                             "pluperfect", "continuative"}),
    "IMPERFECT_SUBJUNCTIVE": ({"subjunctive", "past"},
                              {"future", "perfect", "compound", "progressive", "pluperfect"}),
    "FUTURE_SUBJUNCTIVE": ({"subjunctive", "future"},
                           {"past", "present", "perfect", "compound", "progressive"}),
    "PERSONAL_INFINITIVE": ({"infinitive", "personal"}, {"compound", "impersonal"}),
}
PERSON_TAGS = {
    "YO": {"first-person", "singular"}, "TU": {"second-person", "singular"},
    "EL": {"third-person", "singular"}, "NOSOTROS": {"first-person", "plural"},
    "VOSOTROS": {"second-person", "plural"}, "ELLOS": {"third-person", "plural"},
}
FORM_EXCLUDE = {"archaic", "obsolete", "dated", "impersonal", "imperative",
                "anterior", "pluperfect", "participle", "gerund", "negative", "affirmative"}
ENTRY_EXCLUDE = {"archaic", "obsolete", "dated", "rare", "misspelling"}

VOWELS = "aeiouáéíóúâêôãõàü"
PLAIN = {"á": "a", "é": "e", "í": "i", "ó": "o", "ú": "u", "â": "a", "ê": "e", "ô": "o",
         "ã": "a", "õ": "o", "à": "a", "ü": "u"}
# Portuguese writes a circumflex where the stressed vowel is closed, which is the regular case
# (comêssemos, fôssemos). The open-vowel irregulars (tivéssemos, puséssemos) are carried as explicit
# overrides on the derived cell instead -- see the Kotlin engine's note.
ACCENT = {"a": "á", "e": "ê", "i": "í", "o": "ô", "u": "ú"}

PRESENT_AR = ["o", "as", "a", "amos", "ais", "am"]
PRESENT_ER = ["o", "es", "e", "emos", "eis", "em"]
PRESENT_IR = ["o", "es", "e", "imos", "is", "em"]
# The acute on -ámos is what separates the preterite from the present in pt-PT.
PRETERITE_AR = ["ei", "aste", "ou", "ámos", "astes", "aram"]
PRETERITE_ER = ["i", "este", "eu", "emos", "estes", "eram"]
PRETERITE_IR = ["i", "iste", "iu", "imos", "istes", "iram"]
IMPERFECT_AR = ["ava", "avas", "ava", "ávamos", "áveis", "avam"]
IMPERFECT_ER_IR = ["ia", "ias", "ia", "íamos", "íeis", "iam"]
FUTURE = ["ei", "ás", "á", "emos", "eis", "ão"]
CONDITIONAL = ["ia", "ias", "ia", "íamos", "íeis", "iam"]
SUBJ_AR = ["e", "es", "e", "emos", "eis", "em"]
SUBJ_ER_IR = ["a", "as", "a", "amos", "ais", "am"]
IMPERFECT_SUBJ = ["sse", "sses", "sse", "ssemos", "sseis", "ssem"]
FUTURE_SUBJ = ["r", "res", "r", "rmos", "rdes", "rem"]
PERSONAL_INF = ["", "es", "", "mos", "des", "em"]
STRONG_PRET_ENDINGS = ["e", "este", "e", "emos", "estes", "eram"]

# Both plurals take the written accent in the imperfect subjunctive, unlike Spanish.
ACCENTED_PERSONS = {"NOSOTROS", "VOSOTROS"}

CLASS_ENDINGS = {"ar": "AR", "er": "ER", "ir": "IR", "or": "ER", "ôr": "ER"}

SUPPLETIVE_CLASSES = {"pt_suppletive", "pt_imperfect_irregular", "pt_subj_irregular",
                      "pt_other_irregular"}


def verb_class(infinitive: str) -> str | None:
    for tail in sorted(CLASS_ENDINGS, key=len, reverse=True):
        if infinitive.endswith(tail):
            return CLASS_ENDINGS[tail]
    return None


def class_ending(infinitive: str) -> str | None:
    for tail in sorted(CLASS_ENDINGS, key=len, reverse=True):
        if infinitive.endswith(tail):
            return tail
    return None


def stem_of(infinitive: str) -> str:
    tail = class_ending(infinitive)
    return infinitive[: -len(tail)] if tail else infinitive


def ending(cls: str, tense: str, person: str) -> str:
    i = PERSONS.index(person)
    if tense == "PRESENT":
        return {"AR": PRESENT_AR, "ER": PRESENT_ER, "IR": PRESENT_IR}[cls][i]
    if tense == "PRETERITE":
        return {"AR": PRETERITE_AR, "ER": PRETERITE_ER, "IR": PRETERITE_IR}[cls][i]
    if tense == "IMPERFECT":
        return (IMPERFECT_AR if cls == "AR" else IMPERFECT_ER_IR)[i]
    if tense == "FUTURE":
        return FUTURE[i]
    if tense == "CONDITIONAL":
        return CONDITIONAL[i]
    if tense == "PRESENT_SUBJUNCTIVE":
        return (SUBJ_AR if cls == "AR" else SUBJ_ER_IR)[i]
    if tense == "IMPERFECT_SUBJUNCTIVE":
        return IMPERFECT_SUBJ[i]
    if tense == "FUTURE_SUBJUNCTIVE":
        return FUTURE_SUBJ[i]
    return PERSONAL_INF[i]


def accent_final_vowel(text: str) -> str:
    for i in range(len(text) - 1, -1, -1):
        if text[i] in "aeiou":
            return text[:i] + ACCENT[text[i]] + text[i + 1:]
    return text


def _off_preterite(preterite_3p: str, person: str, endings: list[str], accent: bool) -> str | None:
    """Strip -ram from a third-plural preterite and attach one of [endings]."""
    if not preterite_3p.endswith("ram"):
        return None
    base = preterite_3p[:-3]
    if not base:
        return None
    if accent and person in ACCENTED_PERSONS:
        base = accent_final_vowel(base)
    return base + endings[PERSONS.index(person)]


def imperfect_subjunctive(preterite_3p: str, person: str) -> str | None:
    return _off_preterite(preterite_3p, person, IMPERFECT_SUBJ, accent=True)


def future_subjunctive(preterite_3p: str, person: str) -> str | None:
    return _off_preterite(preterite_3p, person, FUTURE_SUBJ, accent=False)


def personal_infinitive(infinitive: str, person: str) -> str | None:
    if not infinitive:
        return None
    return infinitive + PERSONAL_INF[PERSONS.index(person)]


# Tenses built from another cell: {tense: ((source tense, source person) or None, builder)}.
# A None source means "the infinitive itself".
DERIVED = {
    "IMPERFECT_SUBJUNCTIVE": (("PRETERITE", "ELLOS"), imperfect_subjunctive),
    "FUTURE_SUBJUNCTIVE": (("PRETERITE", "ELLOS"), future_subjunctive),
    "PERSONAL_INFINITIVE": (None, personal_infinitive),
}


def regular(infinitive: str, tense: str, person: str) -> str | None:
    cls = verb_class(infinitive)
    if cls is None:
        return None
    if tense in DERIVED:
        source, build = DERIVED[tense]
        if source is None:
            return build(infinitive, person)
        src = regular(infinitive, *source)
        return build(src, person) if src else None
    base = infinitive if tense in ("FUTURE", "CONDITIONAL") else stem_of(infinitive)
    return base + ending(cls, tense, person)


# --------------------------------------------------------------------------------------------------
# Classification
# --------------------------------------------------------------------------------------------------

STEM_VOWEL_CHANGES = {
    ("e", "i"): "pt_stem_e_i", ("o", "u"): "pt_stem_o_u", ("e", "ei"): "pt_stem_e_ei",
}


def strip_accents(text: str) -> str:
    return "".join(PLAIN.get(c, c) for c in text)


def is_nucleus(text: str, i: int) -> bool:
    """Whether text[i] is a vowel of the syllable nucleus.

    The u of "qu" and "gu" before e/i is orthography, not a sound -- the same rule Spanish needs,
    and for the same reason.
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


def stem_change_class(reg_stem: str, act_stem: str) -> str | None:
    """A stem change keeps the consonant skeleton and rewrites the stressed vowel nucleus."""
    if consonant_skeleton(reg_stem) != consonant_skeleton(act_stem):
        return None
    old = strip_accents(last_vowel_run(reg_stem)[2])
    new = strip_accents(last_vowel_run(act_stem)[2])
    if not old or old == new:
        return None
    return STEM_VOWEL_CHANGES.get((old, new))


def split_regular(form: str, cls: str, tense: str, person: str) -> tuple[str, str] | None:
    """Split a form into (stem, ending) when it carries the ending the rules predict."""
    end = ending(cls, tense, person)
    if end and form.endswith(end) and len(form) > len(end):
        return form[: -len(end)], end
    return None


def strong_stem_class(strong_stem: str) -> str:
    """Portuguese doesn't split its strong preterites by stem vowel the way Spanish does -- fiz,
    disse, trouxe, pude, quis, soube and tive are one teachable family, not three."""
    return "pt_strong_pret"


def classify(infinitive: str, cls: str, tense: str, person: str,
             actual: str, reg: str, forms: dict) -> list[str]:
    """The class(es) of a single deviating cell. Returns ["pt_other_irregular"] when nothing fits."""
    reg_split = split_regular(reg, cls, tense, person)
    reg_stem = reg_split[0] if reg_split else stem_of(infinitive)
    act_split = split_regular(actual, cls, tense, person)

    # Written accent only: pos/pôs, tem/têm. Checked first, because it can happen anywhere.
    if strip_accents(actual) == strip_accents(reg):
        return ["pt_accent_shift"]

    if tense in ("FUTURE", "CONDITIONAL"):
        # Both attach to the infinitive, so both show the same contracted stem: far-, dir-, trar-.
        end = ending(cls, tense, person)
        if actual.endswith(end) and reg.endswith(end):
            return ["pt_fut_stem"]
        return ["pt_other_irregular"]

    if tense == "IMPERFECT":
        return ["pt_imperfect_irregular"]

    if tense == "PRETERITE":
        # A strong preterite is the one whose endings are unstressed: fiz/fizeste, not *fazi.
        strong_end = STRONG_PRET_ENDINGS[PERSONS.index(person)]
        if actual.endswith(strong_end) and len(actual) > len(strong_end):
            return ["pt_strong_pret", "pt_strong_pret_ending"]
        if person in ("YO", "EL") and not act_split:
            # fiz, fez, pôs: the ending vanished entirely.
            return ["pt_strong_pret", "pt_strong_pret_ending"]
        if act_split:
            changed = stem_change_class(reg_stem, act_split[0])
            if changed:
                return [changed]
        return ["pt_strong_pret"]

    if tense == "PRESENT_SUBJUNCTIVE":
        # The conjuntivo is built off the eu form, so an irregular eu carries into all six cells.
        eu = forms.get(("PRESENT", "YO"))
        if eu:
            eu_stem = split_regular(eu, cls, "PRESENT", "YO")
            expected = (eu_stem[0] if eu_stem else eu[:-1]) if eu.endswith("o") else None
            if expected and act_split and act_split[0] == expected:
                return ["pt_subj_eu_stem"]
        if act_split:
            changed = stem_change_class(reg_stem, act_split[0])
            if changed:
                return [changed]
        return ["pt_subj_irregular"]

    if tense == "PRESENT":
        if person == "YO":
            # -car/-gar/-çar and friends keep the sound level across the theme vowel.
            if re.search(r"(ç|qu|gu|c|g)o$", actual) and re.search(r"(c|g|ç)", reg[-3:]):
                if actual.endswith("ço"):
                    return ["pt_eu_c"]
            if actual.endswith("nho"):
                return ["pt_eu_nh"]
            if actual.endswith("ço"):
                return ["pt_eu_c"]
            if actual.endswith("ou"):
                return ["pt_eu_ou"]
            return ["pt_eu_other"]
        if act_split:
            changed = stem_change_class(reg_stem, act_split[0])
            if changed:
                return [changed]
        return ["pt_other_irregular"]

    return ["pt_other_irregular"]


# --------------------------------------------------------------------------------------------------
# Dump quirks
# --------------------------------------------------------------------------------------------------

# The future subjunctive and the personal infinitive are tabulated with their first- and
# third-person singular omitted, because for every verb those two are spelled like the infinitive
# (falar) or the future-subjunctive stem (fizer). They are not missing data -- they are the cells the
# derivation already produces, so the reader fills them in rather than rejecting the verb.
BACKFILL_FROM_RULE = {
    ("FUTURE_SUBJUNCTIVE", "YO"), ("FUTURE_SUBJUNCTIVE", "EL"),
    ("PERSONAL_INFINITIVE", "YO"), ("PERSONAL_INFINITIVE", "EL"),
}


def prefer_rival(tense: str, person: str, kept: str, rival: str) -> str:
    """Which of two forms the dump lists for one cell to keep.

    The only systematic clash in Portuguese is the first-person plural preterite of -ar verbs, where
    the dump carries both spellings: pt-BR *falamos* and pt-PT *falámos*. This profile is European,
    and the accent is the whole point -- without it the cell is homographic with the present.
    """
    if (tense, person) == ("PRETERITE", "NOSOTROS"):
        for candidate in (kept, rival):
            if candidate.endswith("ámos"):
                return candidate
    return kept


# Portuguese Wiktionary writes the two spellings of an -ar preterite first-person plural into a
# single footnoted cell -- "voamos¹ / voámos²" -- where ¹ is Brazilian and ² European. A reader that
# takes the field whole sees a form with a space in it and drops the cell, which costs the verb its
# place in the drill.
_FOOTNOTE_MARKS = "¹²³⁴⁵⁶⁷⁸⁹⁰*†‡"


def clean_form(form: str, tense: str, person: str) -> str | None:
    """One form from a dump cell, or None when the cell holds nothing usable."""
    if "/" not in form and not any(m in form for m in _FOOTNOTE_MARKS):
        return form
    candidates = []
    for part in re.split(r"[/\s]+", form):
        part = "".join(c for c in part if c not in _FOOTNOTE_MARKS).strip()
        if part and " " not in part:
            candidates.append(part)
    if not candidates:
        return None
    chosen = candidates[0]
    for candidate in candidates[1:]:
        chosen = prefer_rival(tense, person, chosen, candidate)
    return chosen
