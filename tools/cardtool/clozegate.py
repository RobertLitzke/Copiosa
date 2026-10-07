"""A faithful port of the app's cloze gate, so an offline pass banks exactly what the app would.

Ported rather than approximated: this decides what the learner is shown, and a looser copy would
quietly fill the bank with items the app itself would have refused. Every rule and constant below
mirrors ``domain/cloze/ClozeGate.kt``; the string helpers mirror ``ClozeText.kt``,
``HighlightTarget.normalizeWord`` and ``domain/text/Sentences.kt``.

Keep it in step with the Kotlin. If ``ClozeGate`` gains a rule, this file is wrong until it does
too.
"""

from __future__ import annotations

import re

# --- ClozeGate constants ---------------------------------------------------

MIN_WORDS = 4
MAX_WORDS = 18
MIN_OTHER_CONTENT_WORDS = 3
COMPREHENSIBLE_FRACTION = 0.75
CONTENT_POS = {"noun", "verb", "adjective", "adverb"}
JUNK = ("{{", "](", "http://", "https://", "|", "<", ">")

# FrequencyPos.cardPosFor
PACK_POS_TO_CARD_POS = {
    "noun": "noun", "verb": "verb", "adj": "adjective",
    "adv": "adverb", "intj": "expression", "phrase": "phrase",
}

REJECTS = (
    "MARKUP", "TOO_SHORT", "TOO_LONG", "ANSWER_NOT_FOUND", "ANSWER_REPEATED",
    "ANSWER_SENTENCE_INITIAL", "ANSWER_NOT_CONTENT_WORD", "UNANCHORED",
    "TOO_LITTLE_CONTEXT", "TOO_HARD",
)

_WHITESPACE = re.compile(r"\s+")
_TOKEN = re.compile(r"\w+", re.UNICODE)


# --- string helpers, mirroring the Kotlin ----------------------------------

def normalize_word(word: str) -> str:
    """HighlightTarget.normalizeWord: lowercase, then trim non-alphanumerics off both ends."""
    lowered = word.lower()
    start, end = 0, len(lowered)
    while start < end and not lowered[start].isalnum():
        start += 1
    while end > start and not lowered[end - 1].isalnum():
        end -= 1
    return lowered[start:end]


def key_of(sentence: str) -> str:
    """ClozeText.keyOf: the unique-index key. Letters, digits and single spaces; answer left in."""
    out: list[str] = []
    last_was_space = False
    for ch in sentence.lower():
        if ch.isalnum():
            out.append(ch)
            last_was_space = False
        elif ch.isspace():
            if not last_was_space and out:
                out.append(" ")
            last_was_space = True
        # Punctuation is dropped without inserting a gap, so "gato," and "gato" agree.
    return "".join(out).strip()


def markup_for(sentence: str, answer: str, answer_start: int) -> str:
    """ClozeText.markupFor: wrap the answer at its offset in {{c1::...}}."""
    end = answer_start + len(answer)
    if answer_start < 0 or end > len(sentence) or answer_start >= end:
        return sentence
    return f"{sentence[:answer_start]}{{{{c1::{answer}}}}}{sentence[end:]}"


_TERMINATORS = "!?…¡¿"


def split_sentences(text: str) -> list[str]:
    """Sentences.split, simplified: break on . ! ? ... at a whitespace boundary."""
    out = []
    for raw_line in text.split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        start = 0
        i = 0
        while i < len(line):
            if line[i] == "." or line[i] in _TERMINATORS:
                end = i
                while end + 1 < len(line) and (line[end + 1] == "." or line[end + 1] in _TERMINATORS):
                    end += 1
                nxt = line[end + 1] if end + 1 < len(line) else None
                if nxt is None or nxt.isspace():
                    segment = line[start:end + 1].strip()
                    if segment:
                        out.append(segment)
                    k = end + 1
                    while k < len(line) and line[k].isspace():
                        k += 1
                    start = k
                    i = k
                    continue
                i = end
            i += 1
        tail = line[start:].strip()
        if tail:
            out.append(tail)
    return out


# --- the gate ---------------------------------------------------------------

def _scorable(token: str) -> bool:
    """Digits and single characters say nothing about whether the sentence is readable."""
    return len(token) > 1 and any(ch.isalpha() for ch in token)


def reject(
    sentence: str,
    answer: str,
    card_id: int | None,
    answer_pos: str | None,
    known_forms: set[str],
    freq: dict[str, tuple[int, str, str]] | None,
    function_words: set[str],
) -> str | None:
    """None when the candidate should be banked, otherwise the ClozeReject name."""
    if any(j in sentence for j in JUNK):
        return "MARKUP"

    raw_tokens = [t for t in _WHITESPACE.split(sentence) if t]
    if len(raw_tokens) < MIN_WORDS:
        return "TOO_SHORT"
    if len(raw_tokens) > MAX_WORDS:
        return "TOO_LONG"

    answer_norm = normalize_word(answer)
    if not answer_norm:
        return "ANSWER_NOT_FOUND"
    tokens = [normalize_word(t) for t in raw_tokens]
    occurrences = tokens.count(answer_norm)
    if occurrences == 0:
        return "ANSWER_NOT_FOUND"
    if occurrences > 1:
        return "ANSWER_REPEATED"
    # Options render lower case, so a capitalised blank would give itself away.
    if tokens[0] == answer_norm:
        return "ANSWER_SENTENCE_INITIAL"

    match = freq.get(normalize_word(answer)) if freq is not None else None
    pos = None
    if match is not None:
        mapped = PACK_POS_TO_CARD_POS.get(match[2], "unknown")
        if mapped != "unknown":
            pos = mapped
    if pos is None and answer_pos:
        stated = answer_pos.strip().lower()
        if stated and stated != "unknown":
            pos = stated
    # No stated part of speech anywhere: refuse rather than guess.
    if pos is None or pos not in CONTENT_POS:
        return "ANSWER_NOT_CONTENT_WORD"

    # The blank has to be worth practising: a word the learner studies, or a frequent one.
    if card_id is None and match is None:
        return "UNANCHORED"

    blank_index = tokens.index(answer_norm)
    others = [t for i, t in enumerate(tokens) if i != blank_index and _scorable(t)]
    if not others:
        return "TOO_LITTLE_CONTEXT"

    if freq is None:
        return None

    content_words = sum(
        1 for t in others
        if (m := freq.get(t)) is not None
        and PACK_POS_TO_CARD_POS.get(m[2], "unknown") in CONTENT_POS
    )
    if content_words < MIN_OTHER_CONTENT_WORDS:
        return "TOO_LITTLE_CONTEXT"

    known = sum(1 for t in others if t in known_forms or t in function_words or t in freq)
    if known / len(others) < COMPREHENSIBLE_FRACTION:
        return "TOO_HARD"

    return None
