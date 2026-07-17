#!/usr/bin/env python3
"""One-off cleanup of a specific hand-built Spanish Anki deck for import into Copiosa.

This deck (``Spanish.apkg``) mixes display cruft and structural card types that don't suit
Copiosa's highlighting / LLM-vocab features. This script rewrites the *note content* into a
clean, structured form **without ever touching review scheduling or history** — it edits only the
``notes`` table (``flds``/``tags``/``sfld``/``csum``/``mod``) and the ``col.tags`` registry, and
leaves ``cards``, ``revlog`` and ``col.crt`` byte-for-byte identical (asserted before writing).

Structured data is carried in namespaced Anki tags (``la:*``) rather than new note fields, so the
deck stays Anki-valid with no note-type surgery and the tags survive round-trips. Copiosa reads
them back on import (see AnkiImporter).

Per note it:
  * picks the Spanish (target) side and records its projected position as ``la:tgt:{0|1}``,
  * classifies the card as ``la:kind:{vocab|phrase|sentence|conjugation|grammar|cloze}``,
  * for vocab/phrase, strips article/gender/POS/region/epicene markers from the Spanish term into
    ``la:gender:`` / ``la:pos:`` / ``la:region:`` tags, and
  * collapses a multi-term front (``(el) cerdo, (el) chancho``) to ``cerdo; chancho`` (primary first;
    Copiosa splits the rest into altForms).

Usage:
    python clean_spanish_apkg.py --input ../Spanish.apkg --output ../Spanish.clean.apkg
    python clean_spanish_apkg.py --input ../Spanish.apkg --report   # dry-run, prints samples only
"""

from __future__ import annotations

import argparse
import hashlib
import html
import os
import re
import shutil
import sqlite3
import tempfile
import time
import unicodedata
import zipfile
from collections import Counter, defaultdict

try:
    import orjson as _json  # noqa: F401

    def loads(s):
        return _json.loads(s)

    def dumps(o):
        return _json.dumps(o).decode()
except ImportError:
    import json as _json

    def loads(s):
        return _json.loads(s)

    def dumps(o):
        return _json.dumps(o, ensure_ascii=False)


FS = "\x1f"  # Anki field separator

# ---- marker patterns -------------------------------------------------------

ARTICLE_GENDER = {
    "el": "m", "un": "m", "los": "mpl", "unos": "mpl",
    "la": "f", "una": "f", "las": "fpl", "unas": "fpl",
    "el/la": "mf", "la/el": "mf", "un/una": "mf",
}
# Leading "(el)" / "(la)" / "(el/la)" ... (optionally &nbsp;/space before the word).
LEADING_PAREN_ART = re.compile(
    r"^\s*\((el/la|la/el|un/una|los|las|unos|unas|el|la|un|una)\)\s*", re.IGNORECASE
)
# Bare leading article: "el sal", "la fruta" (only when not parenthesized).
LEADING_BARE_ART = re.compile(r"^\s*(el|la|los|las)\s+(?=\S)", re.IGNORECASE)
# Trailing "(nf)" / "(adj)" / "(v)" ... part-of-speech / gender suffix.
SUFFIX_POS = re.compile(
    r"\s*\((nf|nm|nc|n|m|f|adj|adv|v|prep|conj|pron|interj|num)\)\s*$", re.IGNORECASE
)
SUFFIX_POS_MAP = {
    "nf": ("noun", "f"), "nm": ("noun", "m"), "nc": ("noun", None), "n": ("noun", None),
    "m": ("noun", "m"), "f": ("noun", "f"),
    "adj": ("adjective", None), "adv": ("adverb", None), "v": ("verb", None),
    "prep": ("preposition", None), "conj": ("conjunction", None),
    "pron": ("pronoun", None), "interj": ("interjection", None), "num": ("numeral", None),
}
# Trailing epicene paren suffix on a person noun: "doctor(a)", "niñ(o/a)".
EPICENE_PAREN = re.compile(r"\(([oa]s?|o/a|a/o)\)\s*$", re.IGNORECASE)
SOUND_RE = re.compile(r"\[sound:[^\]]*\]")
TAG_RE = re.compile(r"<[^>]+>")
WS_RE = re.compile(r"\s+")
BRACKET_RE = re.compile(r"\[([^\]]*)\]")
PAREN_REGION_RE = re.compile(r"\(([^)]*)\)")

# Known region markers (accent/case-insensitive), mapped to a slug Copiosa understands.
REGION_SLUGS = {
    "centroamerica": "centroamerica", "centro america": "centroamerica",
    "sudamerica": "sudamerica", "suramerica": "sudamerica",
    "latinoamerica": "latam", "latino": "latam", "latam": "latam", "am latina": "latam",
    "mexico": "mx", "mex": "mx", "mx": "mx",
    "espana": "spain", "spain": "spain",
    "argentina": "ar", "arg": "ar",
    "colombia": "co", "chile": "cl", "peru": "pe", "venezuela": "ve",
    "caribe": "caribe", "cono sur": "conosur",
}


def strip_accents(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def region_slug(token: str) -> str | None:
    key = strip_accents(token).strip().lower()
    return REGION_SLUGS.get(key)


def plain_text(field: str) -> str:
    """Render an Anki HTML field as display text (mirrors AnkiImporter.htmlToPlainText)."""
    s = SOUND_RE.sub(" ", field)
    s = re.sub(r"</?(?:br|div|p|li|tr|h[1-6]|blockquote)(?:\s[^>]*)?/?>", " ", s, flags=re.I)
    s = TAG_RE.sub("", s)
    s = html.unescape(s.replace("&nbsp;", " "))
    return WS_RE.sub(" ", s).strip()


def strip_html_media(field: str) -> str:
    """Anki's stripHTMLMedia, good enough for sfld/csum (dup detection)."""
    s = SOUND_RE.sub("", field)
    s = re.sub(r'<img[^>]*src="?([^">]+)"?[^>]*>', r" \1 ", s, flags=re.I)
    s = TAG_RE.sub("", s)
    return html.unescape(s.replace("&nbsp;", " ")).strip()


def field_checksum(field0: str) -> int:
    """Anki note csum: first 8 hex of sha1(stripHTMLMedia(field0))."""
    digest = hashlib.sha1(strip_html_media(field0).encode("utf-8")).hexdigest()
    return int(digest[:8], 16)


def split_terms(s: str) -> list[str]:
    """Split on , ; | separators that are not nested inside () or []."""
    out, depth, cur = [], 0, []
    for ch in s:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth = max(0, depth - 1)
        if ch in ",;|" and depth == 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur))
    return [p.strip() for p in out if p.strip()]


# Spelled-out parenthetical gender override, e.g. "(el) agua (feminina)" is actually feminine.
PAREN_GENDER = re.compile(r"\((?:(feminin[ao]|femenin[ao]|fem)|(masculin[ao]|masc))\)", re.IGNORECASE)


# ---- per-term parsing ------------------------------------------------------

class Parsed:
    __slots__ = ("term", "gender", "pos", "region")

    def __init__(self, term, gender=None, pos=None, region=None):
        self.term, self.gender, self.pos, self.region = term, gender, pos, region


def parse_term(raw: str) -> Parsed:
    """Strip article/gender/POS/region/epicene markers from one Spanish term."""
    gender = pos = region = None
    t = plain_text(raw)

    # Region: only brackets/parens whose content is a known region.
    def _region_sub(m):
        nonlocal region
        slug = region_slug(m.group(1))
        if slug:
            region = region or slug
            return " "
        return m.group(0)

    t = BRACKET_RE.sub(_region_sub, t)
    t = PAREN_REGION_RE.sub(_region_sub, t)

    # Spelled-out parenthetical gender override (wins over the article, e.g. euphonic "el agua").
    override = None
    m = PAREN_GENDER.search(t)
    if m:
        override = "f" if m.group(1) else "m"
        t = t[: m.start()] + t[m.end():]

    # Leading parenthesized article -> gender.
    m = LEADING_PAREN_ART.match(t)
    if m:
        gender = ARTICLE_GENDER.get(m.group(1).lower())
        pos = pos or "noun"
        t = t[m.end():]
    else:
        m = LEADING_BARE_ART.match(t)
        if m:
            gender = ARTICLE_GENDER.get(m.group(1).lower())
            pos = pos or "noun"
            t = t[m.end():]

    # Trailing POS/gender suffix "(nf)" etc.
    m = SUFFIX_POS.search(t)
    if m:
        p, g = SUFFIX_POS_MAP[m.group(1).lower()]
        pos = pos or p
        gender = gender or g
        t = t[: m.start()]

    # Epicene: trailing @ or "(a)" -> canonical masculine form, common gender.
    if "@" in t:
        t = re.sub(r"@", "o", t)
        gender = "mf"
        pos = pos or "noun"
    m = EPICENE_PAREN.search(t)
    if m:
        gender = "mf"
        pos = pos or "noun"
        t = t[: m.start()]

    if override:
        gender = override
        pos = pos or "noun"

    return Parsed(WS_RE.sub(" ", t).strip(" ,;|"), gender, pos, region)


# ---- card-kind classification ---------------------------------------------

def structural_kind(model_name: str, template_names: list[str], tags: list[str]) -> str | None:
    """Card kind that is determined by the note type / tags alone (no text heuristic)."""
    name = model_name.lower()
    tmpls = [t.lower() for t in template_names]
    low = [t.lower() for t in tags]
    if "cloze" in name:
        return "cloze"
    if any("grammar" in t for t in tmpls):
        return "grammar"
    if "preterit" in low or "conjugation" in low or "8484b" in name:
        return "conjugation"
    return None


def is_sentence_like(full_text: str, parts: list["Parsed"]) -> bool:
    """A sentence (vs. a synonym list): terminal punctuation, or one long marker-free term."""
    if re.search(r"[.!?¿¡]", full_text):
        return True
    if len(parts) <= 1:
        term = parts[0].term if parts else full_text
        return len(term.split()) >= 5
    return False


# ---- model metadata --------------------------------------------------------

def model_target_field_index(model: dict) -> int | None:
    """Field index that is explicitly the Spanish/target side, by field name, else None."""
    for i, f in enumerate(model["flds"]):
        if "spanish" in f["name"].lower() or "español" in f["name"].lower():
            return i
    return None


SPANISH_ACCENT_RE = re.compile(r"[áéíóúñü¿¡]", re.IGNORECASE)
# Standalone tokens that strongly indicate Spanish and essentially never appear as English glosses
# (note: trailing POS markers like "(v)/(adj)" are NOT a signal — the English glosses carry them too).
SPANISH_FUNC = {
    "de", "del", "al", "por", "para", "la", "el", "los", "las", "un", "una", "unos", "unas",
    "lo", "con", "en", "que", "su", "mi", "tu", "se", "le", "y", "o",
}
# Clearly-English tokens (kept disjoint from Spanish: "a"/"en"/"el" are ambiguous, so excluded).
ENGLISH_FUNC = {
    "the", "an", "of", "to", "in", "on", "by", "for", "with", "from", "into", "onto", "upon",
    "about", "your", "my", "his", "her", "their", "its", "our", "ones", "one's", "oneself",
    "someone", "something", "somebody", "each", "this", "that", "these", "those", "as", "than",
    "when", "while", "up", "off", "out", "down", "over", "you", "we", "they", "be", "do",
}
REFLEXIVE_END = re.compile(r"(arse|erse|irse)$", re.IGNORECASE)   # uniquely Spanish
INFINITIVE_END = re.compile(r"(ar|ir)$", re.IGNORECASE)           # -er omitted (common in English)


def _tokens(plain: str) -> list[str]:
    return [w.strip(".,;:!?¡¿\"'()«»").lower() for w in plain.split()]


def spanish_score(field: str) -> int:
    plain = plain_text(field)
    toks = _tokens(plain)
    score = 0
    if "[sound:" in field:
        score += 2
    if LEADING_PAREN_ART.match(plain):
        score += 2
    if SPANISH_ACCENT_RE.search(plain):
        score += 1
    if SPANISH_FUNC & set(toks):
        score += 1
    if any(REFLEXIVE_END.search(w) for w in toks):
        score += 2
    elif any(INFINITIVE_END.search(w) for w in toks):
        score += 1
    return score


def english_score(field: str) -> int:
    plain = plain_text(field)
    low = plain.lower()
    score = 0
    if re.match(r"^\s*to\s+\w", low):  # English infinitive, e.g. "to refuse to"
        score += 2
    if "'s" in low or "’s" in low:
        score += 1
    if ENGLISH_FUNC & set(_tokens(plain)):
        score += 1
    return score


# Manually classified English-front cards whose Spanish side has no detectable "tell" (no accent,
# article, or recognised function word), so the heuristic ties and would fall back to the Spanish-front
# prior. Listed by their English front text; the Spanish is the other side. (One-off deck, ~26 cards.)
ENGLISH_FRONT_OVERRIDES = {
    "above all", "above all, especially", "again, another time, once more",
    "at every turn, at every moment", "at full speed", "at most", "at times", "blindly",
    "carelessly", "few times, rarely", "if only ...", "mostly", "no longer",
    "not only ... but also", "perhaps", "right away", "right now", "shortly before", "sometime",
    "twice, two times", "undoubtedly, without a doubt", "unparralleled, nothing like it",
    "without fail", "unsellable", "it does not matter.", "it isn't true. it isn't so.",
}


def _norm(s: str) -> str:
    return WS_RE.sub(" ", s.replace("’", "'").replace("‘", "'")).strip().lower()


# Per-model prior for which projected position (0/1) is Spanish, when signals tie.
MODEL_PRIOR = {
    "basic (and reversed card)": 0,
    "basic-f4e28": 0,
    "cloze": 0,
    "basic-dede6": 1,           # English grammar Q -> Spanish answer
    "basic-8484b": 1,           # English prompt -> Spanish conjugation
    "basic (and reversed card)-a2ebf": 1,  # image+English -> Spanish
    "basic-bdfd9": 0,
}


def projected_pair(fields: list[str]) -> list[tuple[int, str]]:
    """The first two non-empty plain-text fields, as (raw_index, plain_text), mirroring import."""
    out = []
    for i, f in enumerate(fields):
        p = plain_text(f)
        if p:
            out.append((i, f))
            if len(out) == 2:
                break
    return out


def pick_target(fields: list[str], model: dict) -> tuple[int, int] | None:
    """Returns (projected_pos, raw_field_index) of the Spanish term, or None if no text fields."""
    pair = projected_pair(fields)
    if not pair:
        return None
    if len(pair) == 1:
        return 0, pair[0][0]
    explicit = model_target_field_index(model)
    if explicit is not None:
        for pos, (raw_i, _) in enumerate(pair):
            if raw_i == explicit:
                return pos, raw_i
    # Manual overrides for no-tell ambiguous cards (the Spanish is the side NOT in the override set).
    if _norm(plain_text(pair[0][1])) in ENGLISH_FRONT_OVERRIDES:
        return 1, pair[1][0]
    if _norm(plain_text(pair[1][1])) in ENGLISH_FRONT_OVERRIDES:
        return 0, pair[0][0]
    # Net Spanish-ness = Spanish signals minus English signals, so an unaccented Spanish phrase like
    # "negarse a" still beats an English front like "to refuse to".
    n0 = spanish_score(pair[0][1]) - english_score(pair[0][1])
    n1 = spanish_score(pair[1][1]) - english_score(pair[1][1])
    if n0 != n1:
        pos = 0 if n0 > n1 else 1
    else:
        pos = MODEL_PRIOR.get(model["name"].lower(), 0)
    return pos, pair[pos][0]


# ---- main pipeline ---------------------------------------------------------

def clean_collection(con: sqlite3.Connection, report_only: bool) -> dict:
    cur = con.cursor()
    models = loads(cur.execute("SELECT models FROM col").fetchone()[0])

    stats = Counter()
    samples = defaultdict(list)
    new_tag_usns = {}

    rows = cur.execute("SELECT id, mid, tags, flds, sfld FROM notes").fetchall()
    updates = []
    for nid, mid, raw_tags, flds, _sfld in rows:
        model = models[str(mid)]
        fields = flds.split(FS)
        tags = [t for t in raw_tags.split() if t and not t.startswith("la:")]

        picked = pick_target(fields, model)
        if picked is None:
            stats["no_text_field"] += 1
            continue
        tgt_pos, tgt_raw = picked

        tmpl_names = [t["name"] for t in model["tmpls"]]
        target_raw = fields[tgt_raw]

        la = [f"la:tgt:{tgt_pos}"]
        kind = structural_kind(model["name"], tmpl_names, tags)
        rewrite = False

        if kind is None:
            # Lexical card. Split on plain text (entity-decoded, so a ';' inside "&nbsp;" isn't a
            # separator), then decide sentence vs synonym-list. Terminal punctuation is the strong
            # sentence signal; a single long term is the weak one (marker word-count would inflate).
            full = plain_text(target_raw)
            parts = [p for p in (parse_term(s) for s in split_terms(full)) if p.term]
            if is_sentence_like(full, parts):
                kind = "sentence"  # leave the field untouched, only tag.
            elif not parts:
                kind = "vocab"
            else:
                primary = parts[0]
                kind = "phrase" if len(primary.term.split()) >= 2 else "vocab"
                rewrite = True
                cleaned_target = "; ".join(p.term for p in parts)
                if primary.gender:
                    la.append(f"la:gender:{primary.gender}")
                if primary.pos:
                    la.append(f"la:pos:{primary.pos}")
                region = next((p.region for p in parts if p.region), None)
                if region:
                    la.append(f"la:region:{region}")
        la.insert(0, f"la:kind:{kind}")

        if rewrite and cleaned_target and cleaned_target != target_raw:
            if len(samples[kind]) < 6:
                samples[kind].append((plain_text(target_raw), cleaned_target, [t for t in la if t != f"la:tgt:{tgt_pos}"]))
            fields[tgt_raw] = cleaned_target
            stats["field_rewritten"] += 1
        new_flds = FS.join(fields)

        all_tags = tags + la
        # Dedup preserving order.
        seen, dedup = set(), []
        for t in all_tags:
            if t not in seen:
                seen.add(t)
                dedup.append(t)
            if t.startswith("la:"):
                new_tag_usns[t] = -1
        new_tags = (" " + " ".join(dedup) + " ") if dedup else ""

        sortf = model.get("sortf", 0)
        new_sfld = strip_html_media(fields[sortf]) if sortf < len(fields) else strip_html_media(fields[0])
        new_csum = field_checksum(fields[0])
        stats[f"kind:{kind}"] += 1
        updates.append((new_flds, new_tags, new_sfld, new_csum, int(time.time()), nid))

    if not report_only:
        cur.executemany(
            "UPDATE notes SET flds=?, tags=?, sfld=?, csum=?, mod=? WHERE id=?", updates
        )
        # Register new tags in col.tags so Anki's browser knows them.
        col_tags = loads(cur.execute("SELECT tags FROM col").fetchone()[0])
        col_tags.update(new_tag_usns)
        cur.execute("UPDATE col SET tags=?", (dumps(col_tags),))
        con.commit()

    stats["notes_total"] = len(rows)
    return {"stats": stats, "samples": samples}


def scheduling_fingerprint(db_path: str) -> str:
    """Hash of everything that MUST stay unchanged: cards, revlog, and col.crt."""
    con = sqlite3.connect(db_path)
    cur = con.cursor()
    h = hashlib.sha256()
    for tbl in ("cards", "revlog"):
        for row in cur.execute(f"SELECT * FROM {tbl} ORDER BY id"):
            h.update(repr(row).encode("utf-8"))
    h.update(str(cur.execute("SELECT crt FROM col").fetchone()[0]).encode())
    con.close()
    return h.hexdigest()


def collection_member(zf: zipfile.ZipFile) -> str:
    for name in ("collection.anki21", "collection.anki2"):
        if name in zf.namelist():
            return name
    raise SystemExit("No collection.anki2/.anki21 found in the apkg.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", required=True)
    ap.add_argument("--output")
    ap.add_argument("--report", action="store_true", help="Dry run: print samples/stats, write nothing.")
    args = ap.parse_args()

    if not args.report and not args.output:
        ap.error("--output is required unless --report")

    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(args.input) as zf:
            member = collection_member(zf)
            names = zf.namelist()
            db_path = os.path.join(tmp, member)
            with open(db_path, "wb") as f:
                f.write(zf.read(member))

        before = scheduling_fingerprint(db_path)

        con = sqlite3.connect(db_path)
        result = clean_collection(con, report_only=args.report)
        con.close()

        after = scheduling_fingerprint(db_path)
        if before != after:
            raise SystemExit("ABORT: scheduling tables (cards/revlog/crt) changed — refusing to write.")

        # Report.
        st = result["stats"]
        print(f"notes processed: {st['notes_total']}   fields rewritten: {st.get('field_rewritten', 0)}")
        print("card kinds:")
        for k in sorted(k for k in st if k.startswith("kind:")):
            print(f"   {k.split(':',1)[1]:13s} {st[k]}")
        print("scheduling fingerprint unchanged:", before[:16], "==", after[:16])
        print()
        for kind, ex in result["samples"].items():
            if not ex:
                continue
            print(f"--- sample cleanups [{kind}] ---")
            for raw, clean, tags in ex:
                print(f"   {raw!r}\n      -> {clean!r}   {' '.join(tags)}")
            print()

        if args.report:
            print("(report only — no file written)")
            return

        # Repackage: copy every original member verbatim except the collection, which we replace.
        with zipfile.ZipFile(args.input) as zin, zipfile.ZipFile(
            args.output, "w", zipfile.ZIP_DEFLATED
        ) as zout:
            for name in names:
                if name == member:
                    zout.write(db_path, member)
                else:
                    zout.writestr(zin.getinfo(name), zin.read(name))
        print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
