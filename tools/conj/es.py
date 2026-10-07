"""Spanish verb morphology for the conjugation generator.

Mirrors `domain/conjugation/SpanishConjugation.kt` and `Conjugator.kt` exactly; the golden CSV this
produces is replayed through the Kotlin engine by `ConjugationGoldenTest`, so a divergence between
the two is a test failure rather than a silently wrong drill.

Everything below was the body of `build_conjugations.py` before the generator was made
language-parametric, moved verbatim.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

LANG = "es"
WIKT = ROOT / "data" / "eswiktionary-raw.jsonl.gz"
FREQ_RAW = ROOT / "data" / "es_frequency_50k.txt"
FREQ_SLICE = ROOT / "tools" / "es_top2000.tsv"
OVERRIDES = ROOT / "tools" / "conjugation_overrides.yaml"
KOTLIN_OUT = ROOT / "app/src/main/java/com/copiosa/domain/conjugation/data/VerbLexiconData.kt"
GOLDEN_OUT = ROOT / "app/src/test/resources/conjugation_golden.csv"
VERIFY_OUT = ROOT / "tools" / "_verify" / "conjugations.json"
KOTLIN_OBJECT = "VerbLexiconData"
KOTLIN_TITLE = "Spanish"

# Tenses built from another cell rather than from the stem: {tense: (source cell, builder)}.
# The builder takes (source_form, person) and returns the derived form.
DERIVED = {"IMPERFECT_SUBJUNCTIVE": (("PRETERITE", "ELLOS"), lambda src, p: imperfect_subjunctive(src, p))}

SUPPLETIVE_CLASSES = {"suppletive", "imperfect_irregular", "subj_irregular", "other_irregular"}

# The Spanish dump tabulates every cell we drill, and lists its rivals standard-form-first.
BACKFILL_FROM_RULE: set[tuple[str, str]] = set()


def prefer_rival(tense: str, person: str, kept: str, rival: str) -> str:
    return kept


def clean_form(form: str, tense: str, person: str) -> str | None:
    """The Spanish dump writes one form per cell, so there is nothing to disentangle."""
    return form

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
