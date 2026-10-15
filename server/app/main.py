"""Ruminate server: library API + resumable file serving + poll triggers."""

import mimetypes
import os
import re
import sqlite3
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from pydantic import BaseModel

from . import db as dbmod
from .config import settings
from .poller import poller
from .subscriptions import load_subscriptions
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
         "file_path, thumb_path, filesize, status, published, downloaded_at, watched "
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


@app.get("/api/settings")
def get_settings():
    return {
        "poll_mode": settings.poll_mode,
        "poll_interval_minutes": settings.poll_interval_minutes,
        "max_resolution": settings.max_resolution,
        "sponsorblock_remove": settings.sponsorblock_remove,
        "sponsorblock_mark": settings.sponsorblock_mark,
        "keep_days": settings.keep_days,
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
        settings.sponsorblock_remove = body.sponsorblock_remove
    if body.sponsorblock_mark is not None:
        settings.sponsorblock_mark = body.sponsorblock_mark
    if body.keep_days is not None:
        settings.keep_days = max(0, body.keep_days)
    return {"ok": True, "settings": get_settings()}


@app.get("/api/subscriptions")
def subscriptions():
    subs = load_subscriptions(Path(settings.data_dir))
    conn = _conn()
    channels = [dict(r) for r in conn.execute(
        "SELECT id, url, title, last_checked FROM channels ORDER BY title").fetchall()]
    conn.close()
    return {
        "inputs": [{"source_url": s.source_url, "channel_id": s.channel_id, "title": s.title}
                   for s in subs],
        "indexed": channels,
    }


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
    conn.close()
    return {"ok": True}


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
