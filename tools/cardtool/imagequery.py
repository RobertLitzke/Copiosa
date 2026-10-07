"""Turning a gloss into an image-search query -- a port of ui/vocab/ImageQuery.kt.

Kept in step with the Kotlin so the desktop picker prefills the same search the in-app picker
would have run for the same card.
"""

from __future__ import annotations

import re

_TAGS = re.compile(r"<[^>]*>")
_PARENS = re.compile(r"\([^)]*\)")
_BRACKETS = re.compile(r"\[[^\]]*]")
_LEADING_ARTICLE = re.compile(r"^(?:to|a|an|the)\s+", re.IGNORECASE)


def image_search_query(gloss: str) -> str:
    text = _TAGS.sub(" ", gloss)
    # Parenthetical and bracketed asides ("(reflexive)", "[fig.]") aren't part of the subject.
    text = _PARENS.sub(" ", text)
    text = _BRACKETS.sub(" ", text)
    # Only the first sense: senses are separated by , ; / |.
    parts = [part for part in re.split(r"[,;/|]", text) if part.strip()]
    text = parts[0].strip() if parts else ""
    # "to call" -> "call", "the dog" -> "dog".
    return _LEADING_ARTICLE.sub("", text).strip()
