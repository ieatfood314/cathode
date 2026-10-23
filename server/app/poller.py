"""Background poller: discovers uploads, downloads them immediately.

Modes (see config.poll_mode, changeable at runtime via PUT /api/settings):
- on_open: no loop; only runs when the app POSTs /api/subscriptions/refresh
  (the Android app does this on every cold open + on pull-to-refresh).
- periodic: loop every poll_interval_minutes.
- instant: loop every 5 minutes regardless of poll_interval_minutes, so new
  drops land on the NAS within minutes of publishing ("the moment they drop").

Cellular note: the *server* uses its wired/NAS link, never the phone's
cellular. The *app* additionally refuses to sync/stream on metered networks
(see android SyncWorker: NetworkType.UNMETERED + Requirements.UNMETERED),
so nothing ever touches cellular data.
"""

import json
import re
import sqlite3
import subprocess
import threading
import time
from pathlib import Path

from . import db as dbmod
from .config import settings
from .downloader import (backfill_dateafter, discover_channel_videos,
                         download_video, resolve_channel_id)
from .subscriptions import Subscription, load_subscriptions

# Rows the scan must leave alone: removed on purpose (retention, watched with
# delete-after-watched) or already found to be un-downloadable by yt-dlp.
# Retrying them re-ran yt-dlp, which exits 0 without writing anything for
# archived ids, and the row came back as a playable-looking 'done' with no file.
SKIP_STATUSES = ("pruned", "deleted", "skipped")
INSTANT_INTERVAL_MINUTES = 5
_ID_IN_NAME = re.compile(r"\[([A-Za-z0-9_-]{6,})\]")
MEDIA_EXTS = (".mp4", ".mkv", ".webm")
THUMB_EXTS = (".jpg", ".webp", ".png")


def is_video_id(vid: str | None) -> bool:
    """Flat channel scans mix in channel entries (UC + 22 chars). Video ids
    are 11 chars and may themselves start with "UC", so check the length."""
    return bool(vid) and not (vid.startswith("UC") and len(vid) == 24)


def _pick_files(files: list[Path]) -> tuple[Path | None, Path | None, Path | None]:
    files = sorted(files)
    main = next((p for p in files if p.suffix.lower() in MEDIA_EXTS), None)
    thumb = next((p for p in files if p.suffix.lower() in THUMB_EXTS), None)
    info = next((p for p in files if p.name.endswith(".info.json")), None)
    return main, thumb, info


class Poller:
    def __init__(self):
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.last_run: int = 0
        self.last_result: dict = {}
        self.running_scan: bool = False
        self._lock = threading.Lock()

    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="cathode-poller")
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _interval(self) -> int:
        mode = (settings.poll_mode or "periodic").lower()
        if mode == "on_open":
            return 0  # no background loop
        if mode == "instant":
            return INSTANT_INTERVAL_MINUTES * 60
        return max(1, settings.poll_interval_minutes or 30) * 60

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

    def _claim(self) -> bool:
        """Atomically take the one scan slot (periodic loop, app refreshes and
        web clicks all race for it)."""
        with self._lock:
            if self.running_scan:
                return False
            self.running_scan = True
            return True

    def scan_once(self) -> dict:
        if not self._claim():
            return {"skipped": "already running"}
        return self._run_claimed()

    def _run_claimed(self) -> dict:
        try:
            data_dir = Path(settings.data_dir)
            media_dir = Path(settings.media_dir)
            media_dir.mkdir(parents=True, exist_ok=True)
            archive = Path(settings.archive_path)
            archive.parent.mkdir(parents=True, exist_ok=True)
            archive.touch(exist_ok=True)

            subs = load_subscriptions(data_dir)
            conn = dbmod.connect(settings.db_path)
            repaired = self._repair_missing_paths(conn, media_dir)
            # One-time migration: new downloads never embed cover art, so
            # once every old file has been checked there is nothing left to do.
            scaled = 0
            if dbmod.kv_get(conn, "cover_strip_done") != "1":
                scaled = self._strip_cover_streams(conn, media_dir)
                dbmod.kv_set(conn, "cover_strip_done", "1")
            discovered = downloaded = failed = 0
            for sub in subs:
                try:
                    r = self._scan_subscription(conn, sub, media_dir, archive)
                    discovered += r[0]
                    downloaded += r[1]
                    failed += r[2]
                except Exception as e:
                    print(f"cathode: scan failed for {sub.source_url}: {e!r}", flush=True)
                    continue
            conn.close()
            self.last_run = int(time.time())
            self.last_result = {
                "subscriptions": len(subs),
                "discovered": discovered,
                "downloaded": downloaded,
                "repaired": repaired,
                "cover_art_stripped": scaled,
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
        if not self._claim():
            return {"started": False, "reason": "already running",
                    "last_result": self.last_result}
        t = threading.Thread(target=self._guarded_scan, daemon=True,
                             name="cathode-scan-once")
        t.start()
        return {"started": True, "last_result": self.last_result}

    def _guarded_scan(self):
        try:
            self._run_claimed()
        except Exception:
            import traceback
            print("cathode: poller task crashed:", traceback.format_exc(), flush=True)

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
            # Live-first channels (The WAN Show and other podcasts) have no
            # /videos tab — their uploads are archived streams under /streams.
            # Only consulted when /videos comes up empty.
            candidates.append(f"https://www.youtube.com/channel/{channel_id}/streams")
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
            # Dates already known locally never cost another yt-dlp lookup.
            known = {r["id"]: r["upload_date"] for r in conn.execute(
                "SELECT id, upload_date FROM videos WHERE channel_id=? "
                "AND upload_date IS NOT NULL AND upload_date != ''", (channel_id,))}
            vids = self._apply_backfill(channel_id, vids, backfill, known)
        disc = down = fail = 0
        for v in vids:
            vid = v.get("id")
            if not is_video_id(vid):
                continue  # playlist/channel entries, not videos
            disc += 1
            cur = conn.execute("SELECT status, file_path FROM videos WHERE id=?", (vid,)).fetchone()
            if cur and (cur["status"] in SKIP_STATUSES
                        or (cur["status"] == "done" and cur["file_path"])):
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
            ok, err = download_video(
                vid, media_dir, archive, dateafter=backfill_dateafter(),
            )
            if ok:
                # NOTE: yt-dlp names files "... [id].mp4" — the id is followed
                # by "]", so the glob must be *id* (NOT *id.* which matches nothing).
                main, thumb, info = _pick_files(
                    [p for p in media_dir.rglob(f"*{vid}*") if p.is_file()])
                if main is None:
                    # yt-dlp exits 0 without writing a file when the id is
                    # already in the download archive or the upload date falls
                    # outside --dateafter. Nothing to serve, so never 'done'.
                    conn.execute(
                        "UPDATE videos SET status='skipped', error=? WHERE id=?",
                        ("yt-dlp skipped it (already archived, or outside the backfill window)",
                         vid))
                    conn.commit()
                    continue
                dbmod.mark_downloaded(
                    conn, vid,
                    str(main.relative_to(media_dir)),
                    str(thumb.relative_to(media_dir)) if thumb else "",
                    str(info.relative_to(media_dir)) if info else "",
                    main.stat().st_size,
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

    def _apply_backfill(self, channel_id: str | None, vids: list[dict], days: int,
                        known: dict[str, str] | None = None) -> list[dict]:
        """Keep only videos dated within the last N days.

        Flat scans carry no dates, so dates come from the free RSS feed first
        and a single batched yt-dlp call second. Earlier versions KEPT
        everything still undatable — when date resolution failed that
        downloaded entire channel archives (years of videos) and filled disk.
        We now drop undatable entries past the newest few (the RSS feed
        already covers the freshest uploads), so backfill never goes
        beyond its window.
        """
        from .downloader import cutoff_ymd, fetch_rss_recent, resolve_upload_dates
        cutoff = cutoff_ymd(days)
        dated: dict[str, str] = dict(known or {})
        if channel_id:
            dated.update(fetch_rss_recent(channel_id))
        flat_ids = [v.get("id") for v in vids if is_video_id(v.get("id"))]
        missing = [i for i in flat_ids if i not in dated]
        if missing:
            dated.update(resolve_upload_dates(missing))
        grace = max(1, settings.max_videos_per_channel_scan)
        kept = []
        for idx, v in enumerate(vids):
            vid = v.get("id")
            if not is_video_id(vid):
                continue
            d = dated.get(vid)
            if d is None and idx >= grace:
                continue  # undated and not clearly recent — do not download
            if d is not None and d < cutoff:
                continue  # clearly older than the window
            kept.append(v)
        return kept

    def _repair_missing_paths(self, conn: sqlite3.Connection, media_dir: Path) -> int:
        """Re-link done rows whose file paths were never recorded.

        (The old *id.* glob never matched yt-dlp's [id].ext naming, so early
        downloads were marked done with empty paths and skipped forever.)
        Returns the number of rows repaired.
        """
        fixed = 0
        rows = conn.execute(
            "SELECT id FROM videos WHERE status='done' AND (file_path IS NULL OR file_path='')").fetchall()
        if not rows:
            return 0
        # One walk of the library instead of one per row.
        by_id: dict[str, list[Path]] = {}
        for p in media_dir.rglob("*"):
            m = _ID_IN_NAME.search(p.name)
            if m and p.is_file():
                by_id.setdefault(m.group(1), []).append(p)
        for row in rows:
            vid = row["id"]
            main, thumb, info = _pick_files(by_id.get(vid, []))
            if not main:
                continue
            dbmod.mark_downloaded(
                conn, vid, str(main.relative_to(media_dir)),
                str(thumb.relative_to(media_dir)) if thumb else "",
                str(info.relative_to(media_dir)) if info else "",
                main.stat().st_size if main.exists() else 0,
            )
            self._enrich_from_info(conn, vid, info)
            fixed += 1
        return fixed

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
        self._rmdir_empty(media_dir)

    def _rmdir_empty(self, media_dir: Path):
        for d in media_dir.iterdir():
            try:
                if d.is_dir() and not any(d.iterdir()):
                    d.rmdir()
            except OSError:
                pass

    def _strip_cover_streams(self, conn: sqlite3.Connection, media_dir: Path) -> int:
        """Remove embedded cover-art video streams that break phone playback.

        Early thumbnails were embedded into the mp4, which adds a second
        video stream (mjpeg cover art). ExoPlayer on the phone selects that
        track as the video — audio plays, video shows black. One-time pass
        over all done videos: ffprobe finds offenders, and ffmpeg remuxes
        without the cover stream (stream copy, no re-encode, seconds per
        file). Idempotent and cheap to re-run.
        """
        PROBE = ["ffprobe", "-v", "error", "-select_streams", "v",
                 "-show_entries", "stream=index,codec_name", "-of", "csv=p=0"]
        COVER_CODECS = ("mjpeg", "png", "gif")
        fixed = 0
        rows = conn.execute(
            "SELECT id, file_path FROM videos WHERE status='done' AND file_path != ''"
        ).fetchall()
        for row in rows:
            path = media_dir / row["file_path"]
            if not path.is_file() or path.suffix.lower() != ".mp4":
                continue  # remux-to-mp4 only applies to mp4 files
            try:
                proc = subprocess.run(PROBE + [str(path)], capture_output=True,
                                      text=True, timeout=30)
                streams = [l.strip().split(",", 1) for l in proc.stdout.splitlines()
                           if "," in l]
                codecs = [c for _, c in streams]
                if len(codecs) <= 1 or not any(c in COVER_CODECS for c in codecs):
                    continue
                # Keep the first real video stream, wherever the cover sits.
                real = next((i for i, c in streams if c not in COVER_CODECS), None)
                if real is None:
                    continue
                tmp = path.with_suffix(path.suffix + ".covers.tmp")
                execs = ["ffmpeg", "-y", "-i", str(path), "-map", f"0:{real}",
                         "-map", "0:a?", "-map", "0:s?", "-c", "copy", "-f", "mp4", str(tmp)]
                proc2 = subprocess.run(execs, capture_output=True, timeout=600)
                if proc2.returncode == 0 and tmp.exists() and tmp.stat().st_size > path.stat().st_size * 0.9:
                    tmp.replace(path)
                    fixed += 1
                elif tmp.exists():
                    tmp.unlink()
            except Exception:
                continue
        if fixed:
            conn.commit()
        return fixed


poller = Poller()
