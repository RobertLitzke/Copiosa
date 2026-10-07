"""Reading part of speech and gender out of the card text itself.

Two different jobs live here, and the difference matters:

* **Markers** are leftover annotation the structured columns replace -- a trailing ``(adj)``, a
  leading ``(la)``. These are parsed *and stripped*; the column now carries the fact.
* **Hints** are ordinary English that happens to reveal the part of speech -- ``(to) fall``,
  ``(the) smoke``. These are read but *left alone*: they are natural gloss text, and deleting
  them would change 300 cards' visible wording for no gain.

The marker patterns come from ``tools/clean_spanish_apkg.py``, which did this same job once
against the original .apkg import; the regexes are shared rather than re-derived.
"""

from __future__ import annotations

import re

# Trailing/inline part-of-speech abbreviation: "(adj)", "(nf)", "(v)". The gender-bearing ones
# (nm/nf/m/f) also settle the gender, which is why the map's values are pairs.
POS_MARKER = re.compile(
    r"\s*\(\s*(nf|nm|nc|adj|adjective|adv|adverb|prep|conj|pron|interj|num|noun|verb|v|n|m|f)\s*\)",
    re.IGNORECASE,
)
POS_MARKER_MAP: dict[str, tuple[str, str | None]] = {
    "nf": ("noun", "F"), "nm": ("noun", "M"), "nc": ("noun", None),
    "n": ("noun", None), "noun": ("noun", None),
    "m": ("noun", "M"), "f": ("noun", "F"),
    "adj": ("adjective", None), "adjective": ("adjective", None),
    "adv": ("adverb", None), "adverb": ("adverb", None),
    "v": ("verb", None), "verb": ("verb", None),
    "prep": ("grammar", None), "conj": ("grammar", None),
    "pron": ("grammar", None), "num": ("grammar", None),
    "interj": ("expression", None),
}

# Leading article on a Spanish headword: "(el) perro", "(el/la) artista", "la fruta".
ARTICLE_GENDER = {
    "el": "M", "un": "M", "los": "MPL", "unos": "MPL",
    "la": "F", "una": "F", "las": "FPL", "unas": "FPL",
    "el/la": "MF", "la/el": "MF", "un/una": "MF",
}
LEADING_PAREN_ARTICLE = re.compile(
    r"^\s*\(\s*(el/la|la/el|un/una|los|las|unos|unas|el|la|un|una)\s*\)\s*", re.IGNORECASE
)
LEADING_BARE_ARTICLE = re.compile(r"^\s*(el|la|los|las)\s+(?=\S)", re.IGNORECASE)

# English gloss shapes that give away the part of speech without being markers.
GLOSS_VERB = re.compile(r"^\s*\(?\s*to\s*\)?\s+\S", re.IGNORECASE)
GLOSS_NOUN = re.compile(r"^\s*\(?\s*(?:the|a|an)\s*\)?\s+\S", re.IGNORECASE)
# A conjugation gloss names its subject: "He/She/It finished", "I brought (traer)". Without this
# the dump reads the accented preterite as whatever unaccented noun it resembles.
GLOSS_CONJUGATED = re.compile(
    r"^\s*\(?\s*(?:I|you|he|she|it|we|they|he/she/it|he/she|s?he)\b", re.IGNORECASE
)

# A card whose target side is a whole sentence: leading-article stripping must not touch it,
# or "Los gatos llegaron a la ciudad." becomes "gatos llegaron a la ciudad."
SENTENCE_END = re.compile(r"[.!?¿¡]")
SPANISH_INFINITIVE = re.compile(r"^[a-záéíóúñü]+(ar|er|ir|arse|erse|irse)$", re.IGNORECASE)

# Endings that mark a participle or a diminutive -- words the dump sometimes lists only as nouns
# (*hervido* "a boiled dish", *calentito*) while the card plainly means the adjective. Used to
# withhold a gender, not to withhold the part of speech.
ADJECTIVAL_SUFFIX = re.compile(r"(ad[oa]s?|id[oa]s?|it[oa]s?|os[oa]s?)$", re.IGNORECASE)


def looks_adjectival(word: str) -> bool:
    return bool(ADJECTIVAL_SUFFIX.search(word.strip()))


def looks_like_sentence(text: str) -> bool:
    return len(text.split()) > 4 or bool(SENTENCE_END.search(text)) or "{{c" in text


def strip_pos_markers(text: str) -> tuple[str, str | None, str | None]:
    """Removes ``(adj)``-style markers, returning (cleaned text, pos, gender)."""
    pos: str | None = None
    gender: str | None = None

    def take(match: re.Match) -> str:
        nonlocal pos, gender
        found_pos, found_gender = POS_MARKER_MAP[match.group(1).lower()]
        pos = pos or found_pos
        gender = gender or found_gender
        return ""

    cleaned = POS_MARKER.sub(take, text)
    return re.sub(r"\s{2,}", " ", cleaned).strip(), pos, gender


def strip_leading_article(text: str) -> tuple[str, str | None]:
    """Removes a leading article from a headword, returning (cleaned text, gender).

    Refuses on anything sentence-shaped -- most leading "Los ..." in this deck are example
    sentences, not articles.
    """
    if looks_like_sentence(text):
        return text, None
    match = LEADING_PAREN_ARTICLE.match(text)
    if match:
        return text[match.end():].strip(), ARTICLE_GENDER.get(match.group(1).lower())
    match = LEADING_BARE_ARTICLE.match(text)
    if match:
        return text[match.end():].strip(), ARTICLE_GENDER.get(match.group(1).lower())
    return text, None


def gloss_hint(gloss: str) -> str | None:
    """The part of speech an English gloss implies, if any."""
    if GLOSS_VERB.match(gloss) or GLOSS_CONJUGATED.match(gloss):
        return "verb"
    if GLOSS_NOUN.match(gloss):
        return "noun"
    return None


def shape_hint(target: str, gender: str | None, card_kind: str) -> str | None:
    """A part-of-speech hint from the card's own shape, weakest of the three sources."""
    if card_kind == "CONJUGATION":
        return "verb"
    if gender:
        # Only nouns carry gender in this schema, so an existing gender settles the question.
        return "noun"
    if card_kind == "PHRASE" or " " in target.strip():
        return "phrase"
    if SPANISH_INFINITIVE.match(target.strip()):
        return "verb"
    return None
