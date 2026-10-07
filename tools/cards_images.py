#!/usr/bin/env python3
"""A tiny desktop web app for putting a picture on every card that wants one.

    python cards_images.py            # then open http://127.0.0.1:8765

Works through the *pulled* database (cards_sync.py), so nothing here touches the phone: images
land in ``dbpull/anki_media/`` and ``cards_sync.py push`` carries them over with the database.

Images are produced exactly the way ``ImageSearchRepository.attachImage`` produces them -- longest
edge 384px, JPEG quality 85, named ``la_img_<cardId>_<epochMs>.jpg`` -- so a card imaged here is
indistinguishable from one imaged in the app, and travels through .apkg export the same way.

One keystroke per card is the whole design: 1-8 picks a result, s skips, / refocuses the search.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import time
import unicodedata
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cardtool import commons  # noqa: E402
from cardtool.db import connect, gloss_side, target_side  # noqa: E402
from cardtool.imagequery import image_search_query  # noqa: E402

try:
    from PIL import Image
except ImportError:  # pragma: no cover - a clear message beats a traceback
    print("This tool needs Pillow:  pip install Pillow", file=sys.stderr)
    raise SystemExit(1)

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "dbpull"

# ImageSearchRepository.kt: MAX_EDGE = 384, JPEG_QUALITY = 85.
MAX_EDGE = 384
JPEG_QUALITY = 85


def fold(text: str) -> str:
    lowered = unicodedata.normalize("NFD", text.strip().lower())
    return "".join(ch for ch in lowered if unicodedata.category(ch) != "Mn")


class Queue:
    """The imageless cards, grouped so a direction-pair is imaged once and shares the picture."""

    def __init__(self, db: Path, media: Path):
        self.db = db
        self.media = media
        self.media.mkdir(parents=True, exist_ok=True)
        self.reload()

    def reload(self) -> None:
        conn = connect(str(self.db), readonly=True)
        try:
            rows = conn.execute(
                """
                SELECT * FROM flashcards
                WHERE imageFile IS NULL AND suspended = 0
                  AND cardKind IN ('VOCAB', 'PHRASE')
                ORDER BY dueAt, id
                """
            ).fetchall()
        finally:
            conn.close()

        items: dict[tuple[str, str], dict] = {}
        for row in rows:
            target = (target_side(row) or "").strip()
            gloss = (gloss_side(row) or "").strip()
            if not target:
                continue
            key = (fold(target), fold(gloss))
            item = items.get(key)
            if item is None:
                item = {
                    "id": row["id"],
                    "ids": [],
                    "term": target,
                    "gloss": gloss,
                    "pos": row["partOfSpeech"],
                    "gender": row["gender"],
                    "query": image_search_query(gloss) or target,
                }
                items[key] = item
            item["ids"].append(row["id"])
        self.items = list(items.values())

    def pending(self, pos: str | None) -> list[dict]:
        """Skipping is session-local and lives in the page; the server only drops what's done."""
        if not pos:
            return list(self.items)
        return [item for item in self.items if (item["pos"] or "") == pos]

    def attach(self, item_id: int, data: bytes) -> str:
        item = next((i for i in self.items if i["id"] == item_id), None)
        if item is None:
            raise KeyError(f"card {item_id} is not in the queue")
        name = f"la_img_{item_id}_{int(time.time() * 1000)}.jpg"
        (self.media / name).write_bytes(downscale(data))

        conn = connect(str(self.db))
        try:
            conn.executemany(
                "UPDATE flashcards SET imageFile = ? WHERE id = ?",
                [(name, card_id) for card_id in item["ids"]],
            )
            conn.commit()
        finally:
            conn.close()
        self.items = [i for i in self.items if i["id"] != item_id]
        return name


def downscale(data: bytes) -> bytes:
    """Longest edge to MAX_EDGE, re-encoded as JPEG -- the app's own sizing."""
    image = Image.open(io.BytesIO(data))
    # Transparency and palettes don't survive JPEG; flatten onto white first.
    if image.mode in ("RGBA", "LA", "P"):
        image = image.convert("RGBA")
        canvas = Image.new("RGB", image.size, (255, 255, 255))
        canvas.paste(image, mask=image.split()[-1])
        image = canvas
    else:
        image = image.convert("RGB")

    longest = max(image.size)
    if longest > MAX_EDGE:
        scale = MAX_EDGE / longest
        image = image.resize(
            (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
            Image.LANCZOS,
        )
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=JPEG_QUALITY)
    return buffer.getvalue()


PAGE = r"""<!doctype html>
<meta charset="utf-8">
<title>Card images</title>
<style>
  :root { color-scheme: dark; --bg:#1b1614; --card:#2a2320; --line:#4a3d38; --ink:#f2e9e4;
          --dim:#b3a49c; --accent:#e2694f; }
  * { box-sizing: border-box; }
  body { margin:0; font:15px/1.5 system-ui,Segoe UI,sans-serif; background:var(--bg); color:var(--ink); }
  header { padding:14px 20px; border-bottom:1px solid var(--line); display:flex; gap:16px;
           align-items:baseline; flex-wrap:wrap; position:sticky; top:0; background:var(--bg); z-index:2; }
  h1 { font-size:17px; margin:0; font-weight:600; }
  .count { color:var(--dim); font-variant-numeric:tabular-nums; }
  select, input, button { font:inherit; background:var(--card); color:var(--ink);
           border:1px solid var(--line); border-radius:8px; padding:7px 11px; }
  button { cursor:pointer; }
  button:hover { border-color:var(--accent); }
  main { padding:20px; max-width:1100px; }
  .term { font-size:30px; font-weight:600; }
  .gloss { color:var(--dim); font-size:17px; margin-top:2px; }
  .chips { margin-top:8px; display:flex; gap:6px; }
  .chip { background:var(--card); border:1px solid var(--line); border-radius:999px;
          padding:2px 10px; font-size:12px; color:var(--dim); }
  .searchrow { margin:18px 0 14px; display:flex; gap:8px; }
  .searchrow input { flex:1; }
  .grid { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; }
  figure { margin:0; background:var(--card); border:1px solid var(--line); border-radius:10px;
           overflow:hidden; cursor:pointer; }
  figure:hover { border-color:var(--accent); }
  figure img { width:100%; height:150px; object-fit:cover; display:block; background:#000; }
  figcaption { padding:5px 8px; font-size:11px; color:var(--dim); white-space:nowrap;
               overflow:hidden; text-overflow:ellipsis; }
  .key { display:inline-block; min-width:16px; color:var(--accent); font-weight:700; }
  .drop { margin-top:16px; border:1.5px dashed var(--line); border-radius:10px; padding:16px;
          text-align:center; color:var(--dim); font-size:13px; }
  .drop.over { border-color:var(--accent); color:var(--ink); }
  .msg { margin-top:12px; color:var(--dim); min-height:1.4em; font-size:13px; }
  .done { padding:40px 0; font-size:20px; }
  kbd { background:var(--card); border:1px solid var(--line); border-radius:4px; padding:1px 5px;
        font-size:11px; }
</style>
<header>
  <h1>Card images</h1>
  <span class="count" id="count"></span>
  <select id="pos">
    <option value="">every card</option>
    <option value="noun">nouns only</option>
    <option value="verb">verbs</option>
    <option value="adjective">adjectives</option>
    <option value="phrase">phrases</option>
  </select>
  <button id="skip">Skip <kbd>s</kbd></button>
  <span class="count"><kbd>1</kbd>-<kbd>8</kbd> pick &middot; <kbd>/</kbd> search</span>
</header>
<main id="main"></main>
<script>
let queue = [], current = null, results = [], busy = false;

async function load() {
  const pos = document.getElementById('pos').value;
  const r = await fetch('/api/cards?pos=' + encodeURIComponent(pos));
  queue = await r.json();
  next();
}

function next() {
  current = queue.shift() || null;
  document.getElementById('count').textContent =
    current ? queue.length + 1 + ' left' : 'nothing left';
  render();
  if (current) search(current.query);
}

function render() {
  const main = document.getElementById('main');
  if (!current) { main.innerHTML = '<p class="done">Queue empty. Run <code>cards_sync.py push</code>.</p>'; return; }
  const chips = [current.pos, current.gender].filter(Boolean)
    .map(c => '<span class="chip">' + esc(c) + '</span>').join('');
  main.innerHTML = `
    <div class="term">${esc(current.term)}</div>
    <div class="gloss">${esc(current.gloss)}</div>
    <div class="chips">${chips}</div>
    <div class="searchrow">
      <input id="q" value="${esc(current.query)}">
      <button onclick="search(document.getElementById('q').value)">Search</button>
    </div>
    <div class="grid" id="grid"></div>
    <div class="drop" id="drop">Drop an image here, or paste one with <kbd>Ctrl</kbd>+<kbd>V</kbd></div>
    <div class="msg" id="msg"></div>`;
  wireDrop();
  document.getElementById('q').addEventListener('keydown', e => {
    if (e.key === 'Enter') search(e.target.value);
    e.stopPropagation();
  });
}

function esc(s) {
  return (s || '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
}

async function search(q) {
  const grid = document.getElementById('grid');
  if (!grid) return;
  grid.innerHTML = '<p class="count">searching…</p>';
  const r = await fetch('/api/search?q=' + encodeURIComponent(q));
  results = await r.json();
  if (!results.length) { grid.innerHTML = '<p class="count">no results</p>'; return; }
  grid.innerHTML = results.map((x, i) => `
    <figure onclick="attach(${i})">
      <img loading="lazy" src="/api/thumb?url=${encodeURIComponent(x.thumb)}">
      <figcaption><span class="key">${i + 1}</span> ${esc(x.title)}</figcaption>
    </figure>`).join('');
}

async function attach(i) {
  if (busy || !current || !results[i]) return;
  busy = true;
  msg('attaching…');
  const r = await fetch('/api/attach', {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({id: current.id, url: results[i].url})
  });
  busy = false;
  if (!r.ok) { msg('failed: ' + await r.text()); return; }
  next();
}

async function upload(file) {
  if (busy || !current || !file) return;
  busy = true;
  msg('uploading…');
  const r = await fetch('/api/attach?id=' + current.id, {method: 'POST', body: file});
  busy = false;
  if (!r.ok) { msg('failed: ' + await r.text()); return; }
  next();
}

function msg(text) { const m = document.getElementById('msg'); if (m) m.textContent = text; }

function wireDrop() {
  const drop = document.getElementById('drop');
  if (!drop) return;
  drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', e => {
    e.preventDefault(); drop.classList.remove('over');
    upload(e.dataTransfer.files[0]);
  });
}

document.addEventListener('paste', e => {
  const item = [...(e.clipboardData?.items || [])].find(i => i.type.startsWith('image/'));
  if (item) upload(item.getAsFile());
});

document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') return;
  if (e.key >= '1' && e.key <= '8') attach(+e.key - 1);
  else if (e.key === 's') next();
  else if (e.key === '/') { e.preventDefault(); document.getElementById('q')?.focus(); }
});

document.getElementById('skip').onclick = () => next();
document.getElementById('pos').onchange = load;
load();
</script>
"""


class Handler(BaseHTTPRequestHandler):
    queue: Queue

    def log_message(self, fmt, *args):  # quieter than the default access log
        pass

    def _send(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, payload, code: int = 200) -> None:
        self._send(code, json.dumps(payload).encode("utf-8"), "application/json; charset=utf-8")

    def _error(self, code: int, message: str) -> None:
        self._send(code, message.encode("utf-8"), "text/plain; charset=utf-8")

    def do_GET(self) -> None:
        url = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(url.query)
        try:
            if url.path == "/":
                self._send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
            elif url.path == "/api/cards":
                pos = (params.get("pos") or [""])[0]
                self._json(self.queue.pending(pos or None))
            elif url.path == "/api/search":
                query = (params.get("q") or [""])[0]
                self._json(commons.search(query))
            elif url.path == "/api/thumb":
                target = (params.get("url") or [""])[0]
                self._send(200, commons.fetch(target), "image/jpeg")
            else:
                self._error(404, "no")
        except Exception as error:  # a broken search shouldn't take the server down
            self._error(500, f"{type(error).__name__}: {error}")

    def do_POST(self) -> None:
        url = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(url.query)
        if url.path != "/api/attach":
            self._error(404, "no")
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        try:
            if self.headers.get("Content-Type", "").startswith("application/json"):
                payload = json.loads(body)
                card_id = int(payload["id"])
                data = commons.fetch(payload["url"])
            else:
                card_id = int((params.get("id") or ["0"])[0])
                data = body
            name = self.queue.attach(card_id, data)
            self._json({"file": name})
        except Exception as error:
            self._error(500, f"{type(error).__name__}: {error}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--db", default=str(WORK / "copiosa.db"))
    parser.add_argument("--media", default=str(WORK / "anki_media"))
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    Handler.queue = Queue(Path(args.db), Path(args.media))
    print(f"{len(Handler.queue.items):,} cards want a picture")
    # Loopback only: this serves an editable view of the whole deck.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"open http://127.0.0.1:{args.port}  (ctrl-c to stop)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
