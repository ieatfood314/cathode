"""Background poller: discovers uploads, downloads them immediately.

Modes (see config.poll_mode, changeable at runtime via PUT /api/settings):
- on_open: no loop; only runs when the app POSTs /api/subscriptions/refresh
  (the Android app does this on every cold open + on pull-to-refresh).
- periodic: loop every poll_interval_minutes.
- instant: loop with interval clamped to max(5, poll_interval_minutes) so new
  drops land on the NAS within minutes of publishing ("the moment they drop").

Cellular note: the *server* uses its wired/NAS link, never the phone's
cellular. The *app* additionally refuses to sync/stream on metered networks
(see android SyncWorker: NetworkType.UNMETERED + Requirements.UNMETERED),
so nothing ever touches cellular data.
"""

import sqlite3
import threading
import time
from pathlib import Path

from . import db as dbmod
from .config import settings
from .downloader import discover_channel_videos, download_video, resolve_channel_id
from .subscriptions import Subscription, load_subscriptions


class Poller:
    def __init__(self):
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.last_run: int = 0
        self.last_result: dict = {}
        self.running_scan: bool = False

    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="ruminate-poller")
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _interval(self) -> int:
        mode = (settings.poll_mode or "periodic").lower()
        if mode == "on_open":
            return 0  # no background loop
        mins = max(1, settings.poll_interval_minutes or 30)
        if mode == "instant":
            mins = max(5, mins)
        return mins * 60

    def _loop(self):
        while not self._stop.is_set():
            interval = self._interval()
            if interval <= 0:
                self._stop.wait(10)
                continue
            try:
                self.scan_once()
            except Exception:
                pass
            self._stop.wait(interval)

    def scan_once(self) -> dict:
        if self.running_scan:
            return {"skipped": "already running"}
        self.running_scan = True
        try:
            data_dir = Path(settings.data_dir)
            media_dir = Path(settings.media_dir)
            media_dir.mkdir(parents=True, exist_ok=True)
            archive = Path(settings.archive_path)
            archive.parent.mkdir(parents=True, exist_ok=True)
            archive.touch(exist_ok=True)

            subs = load_subscriptions(data_dir)
            conn = dbmod.connect(settings.db_path)
            discovered = downloaded = failed = 0
            for sub in subs:
                try:
                    r = self._scan_subscription(conn, sub, media_dir, archive)
                    discovered += r[0]
                    downloaded += r[1]
                    failed += r[2]
                except Exception:
                    continue
            conn.close()
            self.last_run = int(time.time())
            self.last_result = {
                "subscriptions": len(subs),
                "discovered": discovered,
                "downloaded": downloaded,
                "failed": failed,
                "at": self.last_run,
            }
            return self.last_result
        finally:
            self.running_scan = False

    def _scan_subscription(self, conn: sqlite3.Connection, sub: Subscription,
                           media_dir: Path, archive: Path) -> tuple[int, int, int]:
        channel_id = sub.channel_id
        title = sub.title
        if not channel_id:
            channel_id, resolved = resolve_channel_id(sub.source_url)
            title = title or resolved
            if not channel_id:
                return (0, 0, 0)
        dbmod.upsert_channel(conn, channel_id, sub.source_url, title)

        vids = discover_channel_videos(sub.source_url, settings.max_videos_per_channel_scan)
        # Fall back to channel_id URL form if handle lookup returned nothing.
        if not vids and sub.source_url != f"https://www.youtube.com/channel/{channel_id}":
            vids = discover_channel_videos(
                f"https://www.youtube.com/channel/{channel_id}",
                settings.max_videos_per_channel_scan,
            )
        disc = down = fail = 0
        for v in vids:
            vid = v.get("id")
            if not vid or vid.startswith("UC"):
                continue  # playlist/channel entries, not videos
            disc += 1
            cur = conn.execute("SELECT status FROM videos WHERE id=?", (vid,)).fetchone()
            if cur and cur["status"] == "done":
                continue
            dbmod.upsert_video(conn, {
                "id": vid,
                "channel_id": channel_id,
                "channel_title": v.get("channel") or v.get("uploader") or title,
                "title": v.get("title"),
                "upload_date": v.get("upload_date"),
                "duration": v.get("duration") or 0,
                "description": "",
                "published": v.get("timestamp") or 0,
            })
            ok, err = download_video(vid, media_dir, archive)
            if ok:
                files = sorted(media_dir.rglob(f"*{vid}.*"))
                main = next((p for p in files if p.suffix.lower() in (".mp4", ".mkv", ".webm")), None)
                thumb = next((p for p in files if p.suffix.lower() in (".jpg", ".webp", ".png")), None)
                info = next((p for p in files if p.suffix == ".info.json"), None)
                size = main.stat().st_size if main and main.exists() else 0
                dbmod.mark_downloaded(
                    conn, vid,
                    str(main.relative_to(media_dir)) if main else "",
                    str(thumb.relative_to(media_dir)) if thumb else "",
                    str(info.relative_to(media_dir)) if info else "",
                    size,
                )
                down += 1
            else:
                dbmod.mark_failed(conn, vid, err)
                fail += 1
            self._prune(conn, media_dir)
        conn.execute("UPDATE channels SET last_checked=? WHERE id=?", (int(time.time()), channel_id))
        conn.commit()
        return (disc, down, fail)

    def _prune(self, conn: sqlite3.Connection, media_dir: Path):
        if (settings.keep_days or 0) <= 0:
            return
        cutoff = int(time.time()) - settings.keep_days * 86400
        for row in conn.execute(
                "SELECT id, file_path FROM videos WHERE downloaded_at < ? AND downloaded_at > 0",
                (cutoff,)).fetchall():
            try:
                base = (media_dir / (row["file_path"] or "")).with_suffix("")
                for p in media_dir.rglob(f"{base.stem}*"):
                    pass
            except Exception:
                pass


poller = Poller()
