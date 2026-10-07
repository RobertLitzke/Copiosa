"""Wikimedia Commons image search over the standard library.

Mirrors ``data/remote/CommonsApi.kt`` exactly -- same single ``generator=search`` +
``prop=imageinfo`` query, same File: namespace, same descriptive User-Agent (MediaWiki asks for
one and throttles anonymous clients that don't send it). Keyless, so nothing to configure.
"""

from __future__ import annotations

import json
import urllib.parse
import urllib.request
from pathlib import Path

ENDPOINT = "https://commons.wikimedia.org/w/api.php"
PROJECT_URL = "https://github.com/RobertLitzke/Copiosa"


def _contact() -> str:
    """Who to name in the User-Agent: ``copiosa.contact`` from the gitignored local.properties.

    The same key the app build reads, so no address lives in the source; absent, the project
    page stands in.
    """
    try:
        lines = (Path(__file__).resolve().parents[2] / "local.properties").read_text(
            encoding="utf-8").splitlines()
    except OSError:
        return PROJECT_URL
    for line in lines:
        key, _, value = line.partition("=")
        if key.strip() == "copiosa.contact" and value.strip():
            return value.strip()
    return PROJECT_URL


USER_AGENT = f"Copiosa-tools/1.0 (offline card polish; contact {_contact()})"
# BitmapFactory on the device can decode these; svg/tiff/pdf results are dropped.
DECODABLE = {"image/jpeg", "image/png", "image/webp", "image/gif"}
# Only Commons' own file hosts are ever fetched: originals come from upload.wikimedia.org and
# the scaled thumbnails from thumb.wikimedia.org.
ALLOWED_HOSTS = {"upload.wikimedia.org", "thumb.wikimedia.org"}


def _get(url: str, timeout: float = 20.0) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def search(query: str, limit: int = 8, thumb_width: int = 320) -> list[dict]:
    """Ranked candidates for a query: id, title, thumbnail url, full url."""
    query = query.strip()
    if not query:
        return []
    params = {
        "action": "query",
        "format": "json",
        "generator": "search",
        "gsrsearch": query,
        "gsrlimit": str(limit),
        "gsrnamespace": "6",  # File:
        "prop": "imageinfo",
        "iiprop": "url|mime",
        "iiurlwidth": str(thumb_width),
    }
    payload = json.loads(_get(f"{ENDPOINT}?{urllib.parse.urlencode(params)}"))
    pages = ((payload.get("query") or {}).get("pages") or {}).values()

    results = []
    # The pages map is unordered; "index" carries the search rank.
    for page in sorted(pages, key=lambda p: p.get("index", 0)):
        info = (page.get("imageinfo") or [{}])[0]
        thumb = info.get("thumburl")
        if not thumb or info.get("mime") not in DECODABLE:
            continue
        results.append({
            "id": page.get("title", ""),
            "title": (page.get("title") or "").removeprefix("File:"),
            "thumb": thumb,
            "url": info.get("url") or thumb,
        })
    return results


def fetch(url: str, timeout: float = 30.0) -> bytes:
    """Downloads an image, refusing any host but Commons' file server."""
    host = urllib.parse.urlparse(url).hostname or ""
    if host not in ALLOWED_HOSTS:
        raise ValueError(f"refusing to fetch from {host!r}")
    return _get(url, timeout=timeout)
