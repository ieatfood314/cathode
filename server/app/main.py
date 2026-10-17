"""Ruminate server: library API + resumable file serving + poll triggers."""

import mimetypes
import os
import re
import sqlite3
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from pydantic import BaseModel

from . import db as dbmod
from .config import settings
from .poller import poller
from .subscriptions import Subscription, load_subscriptions, normalize_channel_input, parse_opml, parse_takeout_csv
from .web import PAGE as WEB_PAGE

CHUNK = 1024 * 1024


@asynccontextmanager
async def lifespan(app: FastAPI):
    poller.start()
    yield
    poller.stop()


app = FastAPI(title="Ruminate", version="0.1.0", lifespan=lifespan)


def _conn() -> sqlite3.Connection:
    return dbmod.connect(settings.db_path)


@app.get("/api/health")
def health():
    return {
        "ok": True,
        "poll_mode": settings.poll_mode,
        "poll_interval_minutes": settings.poll_interval_minutes,
        "last_run": poller.last_run,
        "last_result": poller.last_result,
    }


@app.get("/api/feed")
def feed(limit: int = Query(100, le=500), unwatched_only: bool = False,
         channel: str | None = None):
    conn = _conn()
    q = ("SELECT id, channel_id, channel_title, title, upload_date, duration, "
         "file_path, thumb_path, filesize, status, published, downloaded_at, watched, "
         "CAST(position_seconds AS INTEGER) AS position_seconds "
         "FROM videos WHERE status='done'")
    args: list = []
    if unwatched_only:
        q += " AND watched=0"
    if channel:
        q += " AND channel_id=?"
        args.append(channel)
    q += " ORDER BY published DESC LIMIT ?"
    args.append(limit)
    rows = [dict(r) for r in conn.execute(q, args).fetchall()]
    conn.close()
    return {"videos": rows}


class ProgressIn(BaseModel):
    position_seconds: int | float | None = None


@app.post("/api/videos/{video_id}/progress")
def save_progress(video_id: str, body: ProgressIn):
    """Store where playback stopped so it resumes everywhere, from anywhere.

    Also auto-marks watched once you're within the last ~5% of the video,
    so no manual button is needed.
    """
    pos = max(0.0, float(body.position_seconds or 0.0))
    conn = _conn()
    row = conn.execute("SELECT duration FROM videos WHERE id=?", (video_id,)).fetchone()
    if not row:
        conn.close()
        raise HTTPException(404, "unknown video")
    auto_watched = False
    if (row["duration"] or 0) > 0 and pos >= row["duration"] * 0.95 - 5:
        conn.execute(
            "UPDATE videos SET position_seconds=?, watched=1 WHERE id=?", (pos, video_id))
        auto_watched = True
    else:
        conn.execute(
            "UPDATE videos SET position_seconds=?, watched=0 WHERE id=?", (pos, video_id))
    conn.commit()
    conn.close()
    return {"ok": True, "position_seconds": int(pos), "watched": auto_watched}


@app.get("/api/videos/{video_id}")
def video_detail(video_id: str):
    conn = _conn()
    row = conn.execute("SELECT * FROM videos WHERE id=?", (video_id,)).fetchone()
    conn.close()
    if not row:
        raise HTTPException(404, "unknown video")
    return dict(row)


class SettingsIn(BaseModel):
    poll_mode: str | None = None  # on_open | periodic | instant
    poll_interval_minutes: int | None = None
    max_resolution: int | None = None
    sponsorblock_remove: str | None = None
    sponsorblock_mark: str | None = None
    keep_days: int | None = None
    backfill_days: int | None = None
    delete_after_watched: bool | None = None


SB_KNOWN = {"all", "sponsor", "intro", "outro", "selfpromo", "preview",
            "interaction", "filler", "music_offtopic", "poi_highlight"}


def _clean_sb(value: str) -> str:
    """Keep only real SponsorBlock categories (typos would fail downloads)."""
    seen: list[str] = []
    for tok in (value or "").split(","):
        t = tok.strip().lower()
        if t in SB_KNOWN and t not in seen:
            seen.append(t)
    return ",".join(seen)


@app.get("/api/settings")
def get_settings():
    return {
        "poll_mode": settings.poll_mode,
        "poll_interval_minutes": settings.poll_interval_minutes,
        "max_resolution": settings.max_resolution,
        "sponsorblock_remove": settings.sponsorblock_remove,
        "sponsorblock_mark": settings.sponsorblock_mark,
        "keep_days": settings.keep_days,
        "backfill_days": settings.backfill_days,
        "delete_after_watched": settings.delete_after_watched,
    }


@app.put("/api/settings")
def put_settings(body: SettingsIn):
    if body.poll_mode in ("on_open", "periodic", "instant"):
        settings.poll_mode = body.poll_mode
    if body.poll_interval_minutes is not None:
        settings.poll_interval_minutes = max(1, min(1440, body.poll_interval_minutes))
    if body.max_resolution is not None:
        settings.max_resolution = max(360, min(2160, body.max_resolution))
    if body.sponsorblock_remove is not None:
        settings.sponsorblock_remove = _clean_sb(body.sponsorblock_remove)
    if body.sponsorblock_mark is not None:
        settings.sponsorblock_mark = _clean_sb(body.sponsorblock_mark)
    if body.keep_days is not None:
        settings.keep_days = max(0, body.keep_days)
    if body.backfill_days is not None:
        settings.backfill_days = max(0, min(3650, body.backfill_days))
    if body.delete_after_watched is not None:
        settings.delete_after_watched = bool(body.delete_after_watched)
    return {"ok": True, "settings": get_settings()}


@app.get("/api/subscriptions")
def subscriptions():
    subs = load_subscriptions(Path(settings.data_dir))
    conn = _conn()
    channels = [dict(r) for r in conn.execute(
        "SELECT id, url, title, last_checked FROM channels ORDER BY title").fetchall()]
    conn.close()
    return {
        "inputs": [{"source_url": s.source_url, "channel_id": s.channel_id,
                    "title": s.title, "origin": s.origin}
                   for s in subs],
        "indexed": channels,
    }


class ChannelIn(BaseModel):
    url: str


@app.post("/api/subscriptions")
def add_subscription(body: ChannelIn):
    """Track one more channel manually — any URL/handle/ID form works."""
    try:
        url = normalize_channel_input(body.url)
    except ValueError as e:
        raise HTTPException(400, str(e))
    data_dir = Path(settings.data_dir)
    data_dir.mkdir(parents=True, exist_ok=True)
    for s in load_subscriptions(data_dir):
        try:
            existing = normalize_channel_input(s.source_url)
        except ValueError:
            existing = (s.source_url or "").strip().rstrip("/")
        if existing == url or (s.channel_id and s.channel_id in url):
            return {"ok": True, "added": False, "reason": "already tracked", "url": url}
    with (data_dir / "channels.txt").open("a", encoding="utf-8") as f:
        f.write(url + "\n")
    return {"ok": True, "added": True, "url": url}


@app.delete("/api/subscriptions")
def remove_subscription(url: str = Query(...)):
    """Stop tracking a manually-added channel (channels.txt entries only)."""
    data_dir = Path(settings.data_dir)
    path = data_dir / "channels.txt"
    if not path.exists():
        raise HTTPException(404, "nothing to remove")
    try:
        norm = normalize_channel_input(url)
    except ValueError:
        norm = (url or "").strip().rstrip("/")
    lines = path.read_text(encoding="utf-8").splitlines()

    def canon(ln: str) -> str:
        try:
            return normalize_channel_input(ln)
        except ValueError:
            return ln.strip().rstrip("/")

    kept = [ln for ln in lines if canon(ln) != norm]
    if len(kept) == len(lines):
        raise HTTPException(
            404, "not a manually-added channel — re-upload your export to change the rest")
    path.write_text("\n".join(kept) + ("\n" if kept else ""), encoding="utf-8")
    return {"ok": True, "removed": True}


@app.post("/api/subscriptions/upload")
async def upload_subscriptions(file: UploadFile = File(...)):
    """Upload a subscription export (Takeout/OPML .opml/.xml/.csv, or .txt list)."""
    name = (file.filename or "").lower()
    raw = await file.read()
    if len(raw) > 5_000_000:
        raise HTTPException(400, "file too big (5 MB max)")
    data_dir = Path(settings.data_dir)
    data_dir.mkdir(parents=True, exist_ok=True)
    try:
        text = raw.decode("utf-8-sig")
    except Exception:
        raise HTTPException(400, "file must be UTF-8 text")
    if name.endswith((".opml", ".xml")):
        dest = data_dir / "subscriptions.opml"
        dest.write_text(text, encoding="utf-8")
        try:
            n = len(parse_opml(dest))
        except Exception:
            dest.unlink(missing_ok=True)
            raise HTTPException(400, "not a valid OPML file")
        return {"ok": True, "file": "subscriptions.opml", "subscriptions": n}
    if name.endswith(".csv"):
        dest = data_dir / "subscriptions.csv"
        dest.write_text(text, encoding="utf-8")
        n = len(parse_takeout_csv(dest))
        if n == 0:
            raise HTTPException(400, "no channels found — is this a Takeout subscriptions.csv?")
        return {"ok": True, "file": "subscriptions.csv", "subscriptions": n}
    if name.endswith(".txt"):
        dest = data_dir / "channels.txt"
        have = set()
        if dest.exists():
            have = {ln.strip() for ln in dest.read_text(encoding="utf-8").splitlines()
                    if ln.strip() and not ln.strip().startswith("#")}
        added = 0
        with dest.open("a", encoding="utf-8") as f:
            for ln in text.splitlines():
                s = ln.strip()
                if s and not s.startswith("#") and s not in have:
                    f.write(s + "\n")
                    have.add(s)
                    added += 1
        return {"ok": True, "file": "channels.txt", "subscriptions": added}
    raise HTTPException(400, "send an .opml, .csv, or .txt file")


@app.post("/api/subscriptions/refresh")
def refresh():
    """Trigger a background scan; returns immediately.

    A full scan (discovery + downloads) takes minutes, far longer than a
    phone's HTTP timeout — so this only *starts* the work. The app then reads
    GET /api/feed (now, and on the next open/sync) for results.
    """
    result = poller.scan_async()
    return {"ok": True, **result}


@app.post("/api/videos/{video_id}/watched")
def mark_watched(video_id: str, watched: bool = True):
    conn = _conn()
    conn.execute("UPDATE videos SET watched=? WHERE id=?", (1 if watched else 0, video_id))
    conn.commit()
    deleted_file = False
    if watched and settings.delete_after_watched:
        row = conn.execute("SELECT file_path FROM videos WHERE id=?", (video_id,)).fetchone()
        if row and row["file_path"]:
            media_dir = Path(settings.media_dir)
            for p in media_dir.rglob(f"*{video_id}*"):
                try:
                    if p.is_file():
                        p.unlink()
                except OSError:
                    pass
            conn.execute(
                "UPDATE videos SET status='deleted', file_path='', thumb_path='', "
                "info_path='', filesize=0 WHERE id=?", (video_id,))
            conn.commit()
            deleted_file = True
    conn.close()
    return {"ok": True, "deleted_server_file": deleted_file}


def _resolve_media(rel: str) -> Path:
    # Guard against path traversal: stay inside media_dir. Note pathlib
    # `base / rel` discards base when rel is absolute, so reject those first.
    if not rel or Path(rel).is_absolute() or ".." in Path(rel).parts:
        raise HTTPException(403, "bad path")
    base = Path(settings.media_dir).resolve()
    p = (base / rel).resolve()
    if p != base and base not in p.parents:
        raise HTTPException(403, "bad path")
    if not p.exists():
        raise HTTPException(404, "file missing (pruned or not downloaded yet)")
    return p


@app.get("/api/videos/{video_id}/stream")
def stream(video_id: str, request: Request):
    conn = _conn()
    row = conn.execute("SELECT file_path FROM videos WHERE id=?", (video_id,)).fetchone()
    conn.close()
    if not row or not row["file_path"]:
        raise HTTPException(404, "not downloaded yet")
    path = _resolve_media(row["file_path"])
    size = path.stat().st_size
    mime, _ = mimetypes.guess_type(str(path))
    mime = mime or "video/mp4"

    # Manual Range handling so dropped connections resume exactly where they
    # stopped (ExoPlayer / DownloadManager both send Range on retry).
    range_h = request.headers.get("range")
    if not range_h:
        return FileResponse(path, media_type=mime, filename=path.name)

    m = re.match(r"bytes=(\d*)-(\d*)", range_h)
    if not m:
        raise HTTPException(416, "bad range")
    start = int(m.group(1)) if m.group(1) else 0
    end = int(m.group(2)) if m.group(2) else size - 1
    end = min(end, size - 1)
    if start >= size or start > end:
        raise HTTPException(416, "bad range")

    def gen():
        with open(path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                data = f.read(min(CHUNK, remaining))
                if not data:
                    break
                remaining -= len(data)
                yield data

    headers = {
        "Content-Range": f"bytes {start}-{end}/{size}",
        "Accept-Ranges": "bytes",
        "Content-Length": str(end - start + 1),
    }
    return StreamingResponse(gen(), status_code=206, headers=headers, media_type=mime)


@app.get("/api/thumbs/{video_id}")
def thumb(video_id: str):
    conn = _conn()
    row = conn.execute("SELECT thumb_path FROM videos WHERE id=?", (video_id,)).fetchone()
    conn.close()
    if not row or not row["thumb_path"]:
        raise HTTPException(404, "no thumbnail")
    path = _resolve_media(row["thumb_path"])
    mime, _ = mimetypes.guess_type(str(path))
    return FileResponse(path, media_type=mime or "image/jpeg")


@app.get("/", response_class=HTMLResponse)
def webapp():
    """Desktop companion UI (same library + settings as the app)."""
    return WEB_PAGE
