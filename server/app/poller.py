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

import json
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

    def scan_async(self) -> dict:
        """Trigger a scan in a background thread and return immediately.

        A full scan (yt-dlp discovery + downloads) can take many minutes, far
        longer than a phone's HTTP timeout — so the API never blocks on it.
        The app calls this on open, then reads GET /api/feed afterwards.
        """
        with threading.Lock():
            if self.running_scan:
                return {"started": False, "reason": "already running",
                        "last_result": self.last_result}
            t = threading.Thread(target=self._guarded_scan, daemon=True,
                                 name="ruminate-scan-once")
            t.start()
            return {"started": True, "last_result": self.last_result}

    def _guarded_scan(self):
        try:
            self.scan_once()
        except Exception:
            pass

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

        # Discover via the canonical channel URL when the id is known. (OPML
        # entries are RSS feed URLs — appending "/videos" to those breaks
        # discovery and costs a full yt-dlp timeout per channel per scan.)
        candidates: list[str] = []
        if channel_id:
            candidates.append(f"https://www.youtube.com/channel/{channel_id}/videos")
        src = (sub.source_url or "").rstrip("/")
        if src and "feeds/videos.xml" not in src and src not in candidates:
            candidates.append(f"{src}/videos" if not src.endswith("/videos") else src)
        vids: list[dict] = []
        limit = settings.max_videos_per_channel_scan
        backfill = max(0, settings.backfill_days or 0)
        if backfill > 0:
            limit = max(limit, 200)  # reach further back; dates filter below
        for url in candidates:
            vids = discover_channel_videos(url, limit)
            if vids:
                break
        if backfill > 0 and vids:
            vids = self._apply_backfill(channel_id, vids, backfill)
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
                self._enrich_from_info(conn, vid, info)
                down += 1
            else:
                dbmod.mark_failed(conn, vid, err)
                fail += 1
            self._prune(conn, media_dir)
        conn.execute("UPDATE channels SET last_checked=? WHERE id=?", (int(time.time()), channel_id))
        conn.commit()
        return (disc, down, fail)

    def _apply_backfill(self, channel_id: str | None, vids: list[dict], days: int) -> list[dict]:
        """Keep only videos from the last N days (plus undatable ones).

        Flat scans carry no dates, so dates come from the free RSS feed first
        and a single batched yt-dlp call second. Anything still undatable is
        kept — safer than silently dropping videos you haven't seen.
        """
        from .downloader import cutoff_ymd, fetch_rss_recent, resolve_upload_dates
        cutoff = cutoff_ymd(days)
        dated: dict[str, str] = fetch_rss_recent(channel_id) if channel_id else {}
        flat_ids = [v.get("id") for v in vids
                    if v.get("id") and not str(v.get("id")).startswith("UC")]
        dated.update(resolve_upload_dates([i for i in flat_ids if i not in dated]))
        kept = []
        for v in vids:
            vid = v.get("id")
            if not vid or str(vid).startswith("UC"):
                continue
            d = dated.get(vid)
            if d is None or d >= cutoff:
                kept.append(v)
        return kept

    def _enrich_from_info(self, conn: sqlite3.Connection, vid: str, info: Path | None):
        """Backfill accurate metadata from yt-dlp's .info.json.

        Flat discovery only yields id/title, so without this every video has
        published=0 (broken feed ordering) and duration=0 (blank UI).
        """
        if info is None or not info.exists():
            return
        try:
            d = json.loads(info.read_text(encoding="utf-8"))
        except Exception:
            return
        conn.execute(
            "UPDATE videos SET title=COALESCE(NULLIF(title,''), ?), upload_date=?, "
            "duration=?, published=?, description=? WHERE id=?",
            (d.get("title"), d.get("upload_date"), d.get("duration") or 0,
             d.get("timestamp") or 0, (d.get("description") or "")[:4000], vid),
        )
        conn.commit()

    def _prune(self, conn: sqlite3.Connection, media_dir: Path):
        """Delete media files older than keep_days (0 = keep forever).

        The download-archive entry is intentionally kept, so pruned videos are
        not re-downloaded on the next scan.
        """
        if (settings.keep_days or 0) <= 0:
            return
        cutoff = int(time.time()) - settings.keep_days * 86400
        for row in conn.execute(
                "SELECT id FROM videos WHERE status='done' "
                "AND downloaded_at < ? AND downloaded_at > 0",
                (cutoff,)).fetchall():
            vid = row["id"]
            # YouTube ids are [A-Za-z0-9_-]; safe to glob on.
            for p in media_dir.rglob(f"*{vid}*"):
                try:
                    if p.is_file():
                        p.unlink()
                except OSError:
                    pass
            conn.execute(
                "UPDATE videos SET status='pruned', file_path='', thumb_path='', "
                "info_path='', filesize=0 WHERE id=?", (vid,))
        conn.commit()
        for d in media_dir.iterdir():
            try:
                if d.is_dir() and not any(d.iterdir()):
                    d.rmdir()
            except OSError:
                pass


poller = Poller()
