#!/usr/bin/env python3
"""Pull the Copiosa database off a debug-installed device, and push it back.

The phone has no ``sqlite3`` binary, so an offline edit round-trips as a whole-file swap:

    python cards_sync.py pull        # force-stop, copy copiosa.db (+ WAL) into dbpull/
    ... run cards_normalize.py / cards_sentences.py / cards_images.py ...
    python cards_sync.py push        # checkpoint, swap the file back, copy new images over

**Do not use the app between pull and push** -- the swap discards anything reviewed in that
window. ``push`` refuses when the device's newest review is younger than the pull, unless
``--force`` says to throw those reviews away anyway.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cardtool.db import DB_FILENAME, EXPECTED_USER_VERSION  # noqa: E402

PACKAGE = "com.copiosa"
REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "dbpull"
STAMP = WORK / "pull_stamp.json"
DEVICE_TMP = "/data/local/tmp/copiosa_push.db"


def adb_path() -> str:
    override = os.environ.get("ADB")
    if override:
        return override
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidate = Path(local) / "Android/Sdk/platform-tools/adb.exe"
        if candidate.exists():
            return str(candidate)
    return "adb"


ADB = adb_path()


def adb(*args: str) -> str:
    result = subprocess.run(
        [ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed:\n{result.stderr or result.stdout}")
    return (result.stdout or "").strip()


def adb_bytes(*args: str, dest: Path) -> int:
    """Runs an adb command that streams binary on stdout, straight into a file."""
    with dest.open("wb") as out:
        result = subprocess.run([ADB, *args], stdout=out, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise RuntimeError(
            f"adb {' '.join(args)} failed:\n{result.stderr.decode(errors='replace')}"
        )
    return dest.stat().st_size


def force_stop() -> None:
    adb("shell", "am", "force-stop", PACKAGE)
    # force-stop returns before the process is actually reaped; a moment here avoids a torn read.
    time.sleep(1.5)


def newest_review_ms(db: Path) -> int:
    """Max lastReviewedAt across cards and cloze sentences -- the 'has the phone moved on' probe."""
    conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        card = conn.execute("SELECT max(lastReviewedAt) FROM flashcards").fetchone()[0] or 0
        try:
            cloze = conn.execute(
                "SELECT max(lastReviewedAt) FROM cloze_sentences"
            ).fetchone()[0] or 0
        except sqlite3.OperationalError:
            cloze = 0
        return max(card, cloze)
    finally:
        conn.close()


def cmd_pull(args: argparse.Namespace) -> int:
    WORK.mkdir(exist_ok=True)
    force_stop()
    db = WORK / DB_FILENAME
    size = adb_bytes("exec-out", "run-as", PACKAGE, "cat", f"databases/{DB_FILENAME}", dest=db)
    print(f"pulled {DB_FILENAME}  {size:,} bytes")

    # WAL sidecars: present only if the app died without checkpointing. Pull them so sqlite can
    # replay; a zero-byte pull means "no sidecar", and leaving it would confuse sqlite.
    for suffix in ("-wal", "-shm"):
        side = WORK / (DB_FILENAME + suffix)
        try:
            n = adb_bytes(
                "exec-out", "run-as", PACKAGE, "cat",
                f"databases/{DB_FILENAME}{suffix}", dest=side,
            )
        except RuntimeError:
            side.unlink(missing_ok=True)
            continue
        if n == 0:
            side.unlink(missing_ok=True)
        else:
            print(f"pulled {DB_FILENAME}{suffix}  {n:,} bytes")

    conn = sqlite3.connect(str(db))
    version = conn.execute("PRAGMA user_version").fetchone()[0]
    integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.commit()
    conn.close()
    for suffix in ("-wal", "-shm"):
        (WORK / (DB_FILENAME + suffix)).unlink(missing_ok=True)

    if version != EXPECTED_USER_VERSION:
        print(f"WARNING: schema v{version}, tools expect v{EXPECTED_USER_VERSION}", file=sys.stderr)
    print(f"schema v{version}, integrity_check: {integrity}")

    shutil.copy2(db, WORK / (DB_FILENAME + ".orig"))
    STAMP.write_text(
        json.dumps(
            {"pulled_at": int(time.time() * 1000), "newest_review_ms": newest_review_ms(db)}
        ),
        encoding="utf-8",
    )
    print(f"snapshot saved as {DB_FILENAME}.orig -- restore from it if a pass goes wrong")
    return 0


def push_file(local: Path, remote_relative: str) -> None:
    """Copies one local file into the app's private storage.

    Pipes through ``run-as`` rather than using ``cp``: the file then lands owned by the app's
    uid, which a plain copy out of /data/local/tmp would not.
    """
    staged = f"/data/local/tmp/{local.name}"
    adb("push", str(local), staged)
    adb("shell", f"cat {staged} | run-as {PACKAGE} sh -c \"cat > {remote_relative}\"")
    adb("shell", "rm", "-f", staged)


def cmd_push(args: argparse.Namespace) -> int:
    db = WORK / DB_FILENAME
    if not db.exists():
        print(f"no {db} -- run `cards_sync.py pull` first", file=sys.stderr)
        return 1

    conn = sqlite3.connect(str(db))
    integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.commit()
    conn.close()
    if integrity != "ok":
        print(f"refusing to push: integrity_check said {integrity!r}", file=sys.stderr)
        return 1
    for suffix in ("-wal", "-shm"):
        (WORK / (DB_FILENAME + suffix)).unlink(missing_ok=True)

    force_stop()

    # Did the phone get reviewed while we were editing? Compare against the pull stamp.
    if STAMP.exists() and not args.force:
        stamp = json.loads(STAMP.read_text(encoding="utf-8"))
        probe = WORK / "device_probe.db"
        adb_bytes("exec-out", "run-as", PACKAGE, "cat", f"databases/{DB_FILENAME}", dest=probe)
        try:
            live = newest_review_ms(probe)
        finally:
            probe.unlink(missing_ok=True)
        if live > stamp.get("newest_review_ms", 0):
            when = time.strftime("%Y-%m-%d %H:%M", time.localtime(live / 1000))
            print(
                f"refusing to push: the device has reviews from {when}, newer than the pull.\n"
                "Pushing would discard them. Re-pull and redo, or pass --force to overwrite.",
                file=sys.stderr,
            )
            return 1

    push_file(db, f"databases/{DB_FILENAME}")
    adb("shell", "run-as", PACKAGE, "rm", "-f",
        f"databases/{DB_FILENAME}-wal", f"databases/{DB_FILENAME}-shm")
    print(f"pushed {DB_FILENAME}  {db.stat().st_size:,} bytes")

    media = WORK / "anki_media"
    if media.is_dir():
        images = sorted(p for p in media.iterdir() if p.is_file())
        for image in images:
            push_file(image, f"files/anki_media/{image.name}")
        print(f"pushed {len(images)} image(s) into files/anki_media/")

    print("done -- launch the app and check it opens without a schema crash")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("pull", help="copy the device database into dbpull/")
    push = sub.add_parser("push", help="swap the edited database back onto the device")
    push.add_argument("--force", action="store_true",
                      help="push even though the device has reviews newer than the pull")
    args = parser.parse_args()
    return {"pull": cmd_pull, "push": cmd_push}[args.command](args)


if __name__ == "__main__":
    raise SystemExit(main())
