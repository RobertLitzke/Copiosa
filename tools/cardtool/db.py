"""Opening and editing an offline copy of the Copiosa database.

Every tool here works on a *pulled* copy (see cards_sync.py), never on the device. The one rule
that matters: any write that changes front/back/partOfSpeech/gender must also NULL
``inflectedForms``, because the app only recomputes that cache for rows where it is NULL
(``FlashcardRepository.ensureFormsBackfilled``). Migrations 30->31 and 32->33 exist because it
went stale; don't add a third.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

DB_FILENAME = "copiosa.db"
# AppDatabase.kt: @Database(version = 40). Room stores this in PRAGMA user_version.
EXPECTED_USER_VERSION = 40

# The editor's dropdown vocabulary (EditCardDialog.kt). A value outside this set renders as the
# raw string rather than a label, so tools must not invent new ones.
POS_VALUES = {
    "noun", "verb", "adjective", "adverb", "phrase", "expression", "grammar", "other", "unknown",
}
POS_UNKNOWN = "unknown"

# VocabChips.kt renders an article for exactly these. "N" is selectable for German.
GENDER_VALUES = {"M", "F", "MF", "MPL", "FPL", "N"}

# Columns whose change invalidates the inflected-forms cache.
_FORM_INPUTS = {"front", "back", "partOfSpeech", "gender"}


class SchemaMismatch(RuntimeError):
    pass


def connect(path: str, readonly: bool = False) -> sqlite3.Connection:
    """Opens a pulled database, replaying any WAL sidecar, and checks the Room schema version."""
    # as_uri() percent-encodes and turns Windows backslashes into a valid file: URI; pasting the
    # raw path after "file:" silently fails to open anything under C:\Users\...
    uri = Path(path).resolve().as_uri()
    if readonly:
        uri += "?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    conn.row_factory = sqlite3.Row
    version = conn.execute("PRAGMA user_version").fetchone()[0]
    if version != EXPECTED_USER_VERSION:
        conn.close()
        raise SchemaMismatch(
            f"{path} is schema v{version}, expected v{EXPECTED_USER_VERSION}. "
            "Re-pull, or update EXPECTED_USER_VERSION if the app's migrations moved on."
        )
    return conn


def missing_pos(value: str | None) -> bool:
    """True when a card has no usable part of speech.

    New cards are stamped with the literal "unknown" rather than NULL (FlashcardRepository.add),
    so "is it missing" is a three-way test, not a NULL check.
    """
    return value is None or value.strip() == "" or value.strip().lower() == POS_UNKNOWN


def set_fields(conn: sqlite3.Connection, card_id: int, **fields) -> None:
    """Updates one card, clearing the inflected-forms cache when its inputs changed."""
    if not fields:
        return
    unknown = set(fields) - {
        "front", "back", "partOfSpeech", "gender", "contextSentence", "imageFile",
    }
    if unknown:
        raise ValueError(f"set_fields refuses to write {sorted(unknown)}")
    if _FORM_INPUTS & set(fields):
        fields["inflectedForms"] = None
    assignments = ", ".join(f"{name} = ?" for name in fields)
    conn.execute(
        f"UPDATE flashcards SET {assignments} WHERE id = ?",
        (*fields.values(), card_id),
    )


def target_side(row: sqlite3.Row) -> str:
    """The target-language text of a card (HighlightTarget.targetTerm)."""
    return row["front"] if row["frontIsTarget"] else row["back"]


def gloss_side(row: sqlite3.Row) -> str:
    """The native-language text of a card."""
    return row["back"] if row["frontIsTarget"] else row["front"]


def counts(conn: sqlite3.Connection, lang: str = "es") -> dict[str, int]:
    """The four numbers this whole exercise is measured by."""
    def one(sql: str) -> int:
        return conn.execute(sql, (lang,)).fetchone()[0]

    return {
        "cards": one("SELECT count(*) FROM flashcards WHERE targetLanguage = ?"),
        "missing_pos": one(
            "SELECT count(*) FROM flashcards WHERE targetLanguage = ? "
            "AND (partOfSpeech IS NULL OR trim(partOfSpeech) = '' OR lower(partOfSpeech) = 'unknown')"
        ),
        "nouns_with_gender": one(
            "SELECT count(*) FROM flashcards WHERE targetLanguage = ? "
            "AND partOfSpeech = 'noun' AND gender IS NOT NULL AND trim(gender) <> ''"
        ),
        "nouns": one(
            "SELECT count(*) FROM flashcards WHERE targetLanguage = ? AND partOfSpeech = 'noun'"
        ),
        "with_example": one(
            "SELECT count(*) FROM flashcards WHERE targetLanguage = ? "
            "AND contextSentence IS NOT NULL AND trim(contextSentence) <> ''"
        ),
        "with_image": one(
            "SELECT count(*) FROM flashcards WHERE targetLanguage = ? AND imageFile IS NOT NULL"
        ),
    }
