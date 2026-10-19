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
import subprocess
import threading
import time
from pathlib import Path

from . import db as dbmod
from .config import settings
from .downloader import (cutoff_ymd, backfill_dateafter, discover_channel_videos,
                         download_video, resolve_channel_id)
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
            repaired = self._repair_missing_paths(conn, media_dir)
            # One-time migrations, both idempotent (ffprobe checks each file):
            reclaimed = self._prune_beyond_backfill(conn, media_dir)
            scaled = self._strip_cover_streams(conn, media_dir)
            discovered = downloaded = failed = 0
            for sub in subs:
                try:
                    r = self._scan_subscription(conn, sub, media_dir, archive)
                    discovered += r[0]
                    downloaded += r[1]
                    failed += r[2]
                except Exception as e:
                    print(f"ruminate: scan failed for {sub.source_url}: {e!r}", flush=True)
                    continue
            conn.close()
            self.last_run = int(time.time())
            self.last_result = {
                "subscriptions": len(subs),
                "discovered": discovered,
                "downloaded": downloaded,
                "repaired": repaired,
                "reclaimed": reclaimed,
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
        except Exception as e:
            import traceback
            print("ruminate: poller task crashed:", traceback.format_exc(), flush=True)

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
            vids = self._apply_backfill(channel_id, vids, backfill)
        disc = down = fail = 0
        for v in vids:
            vid = v.get("id")
            if not vid or vid.startswith("UC"):
                continue  # playlist/channel entries, not videos
            disc += 1
            cur = conn.execute("SELECT status, file_path FROM videos WHERE id=?", (vid,)).fetchone()
            if cur and cur["status"] == "done" and cur["file_path"]:
                continue  # done rows with an empty path are re-repaired below
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
                files = sorted(p for p in media_dir.rglob(f"*{vid}*") if p.is_file())
                main = next((p for p in files if p.suffix.lower() in (".mp4", ".mkv", ".webm")), None)
                thumb = next((p for p in files if p.suffix.lower() in (".jpg", ".webp", ".png")), None)
                info = next((p for p in files if p.name.endswith(".info.json")), None)
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
        dated: dict[str, str] = fetch_rss_recent(channel_id) if channel_id else {}
        flat_ids = [v.get("id") for v in vids
                    if v.get("id") and not str(v.get("id")).startswith("UC")]
        dated.update(resolve_upload_dates([i for i in flat_ids if i not in dated]))
        grace = max(1, settings.max_videos_per_channel_scan)
        kept = []
        for idx, v in enumerate(vids):
            vid = v.get("id")
            if not vid or str(vid).startswith("UC"):
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
        for row in rows:
            vid = row["id"]
            files = sorted(p for p in media_dir.rglob(f"*{vid}*") if p.is_file())
            main = next((p for p in files if p.suffix.lower() in (".mp4", ".mkv", ".webm")), None)
            if not main:
                continue
            thumb = next((p for p in files if p.suffix.lower() in (".jpg", ".webp", ".png")), None)
            info = next((p for p in files if p.name.endswith(".info.json")), None)
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

    def _prune_beyond_backfill(self, conn: sqlite3.Connection, media_dir: Path) -> int:
        """Delete downloads older than the backfill window.

        An earlier backfill bug kept undatable videos and grabbed whole
        channel archives (going back years, tens of GB). One-time repair for
        that hoard: any done video whose upload date predates the backfill
        window is deleted and marked 'pruned' (archive entries are kept, so
        it is not re-downloaded). Runs on every scan; once everything in
        range is reached it's a cheap no-op.
        """
        backfill = max(0, settings.backfill_days or 0)
        if backfill <= 0:
            return 0
        cutoff = cutoff_ymd(backfill)
        rows = conn.execute(
            "SELECT id FROM videos WHERE status='done' "
            "AND upload_date IS NOT NULL AND upload_date != '' AND upload_date < ?",
            (cutoff,)).fetchall()
        n = 0
        for row in rows:
            vid = row["id"]
            for p in media_dir.rglob(f"*{vid}*"):
                try:
                    if p.is_file():
                        p.unlink()
                except OSError:
                    pass
            conn.execute(
                "UPDATE videos SET status='pruned', file_path='', thumb_path='', "
                "info_path='', filesize=0 WHERE id=?", (vid,))
            n += 1
        if n:
            conn.commit()
            self._rmdir_empty(media_dir)
        return n

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
                 "-show_entries", "stream=codec_name", "-of", "csv=p=0"]
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
                codecs = [l.strip() for l in proc.stdout.splitlines() if l.strip()]
                # A bare cover-art stream is mjpeg/png; keep the real video
                # (first hex264 codec) when the main track is also mjpeg.
                if len(codecs) <= 1 or (
                        "mjpeg" not in codecs and "png" not in codecs and "gif" not in codecs):
                    continue
                tmp = path.with_suffix(path.suffix + ".covers.tmp")
                execs = ["ffmpeg", "-y", "-i", str(path), "-map", "0:v:0",
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
