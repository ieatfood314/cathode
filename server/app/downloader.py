"""yt-dlp wrapper with resume-safe flags and SponsorBlock.

Resume strategy (matters on flaky Wi-Fi / server side):
- --continuedl: keep partial files and resume with Range
- --retries 10 --fragment-retries 10 --file-access-retries 10
- --retry-sleep "fragment:exp=1:10" (exponential backoff)
- -N 4 --concurrent-fragments 4 (parallel fragments, one slow fragment
  doesn't stall the whole file)
- --socket-timeout 30
SponsorBlock: remove only sponsor/selfpromo/interaction (unambiguous ads),
mark everything else as chapters (mark is instant, remove re-encodes).

Thumbnails are written as sidecar .jpg files (for /api/thumbs) but NOT
embedded into the mp4: --embed-thumbnail adds a cover-art "video" stream
(mjpeg) to the container, and ExoPlayer picks it as the video track on the
phone — audio plays, video shows black. Never re-add it.
"""

import json
import subprocess
from collections.abc import Callable
from pathlib import Path

from .config import settings

BASE_FLAGS = [
    "--no-progress",
    "--continue",  # NOT --continuedl: removed from yt-dlp (broke every download in 0.1.4)
    "--retries", "10",
    "--fragment-retries", "10",
    "--file-access-retries", "10",
    "--retry-sleep", "fragment:exp=1:10",
    "--socket-timeout", "30",
    "-N", "4",
    "--concurrent-fragments", "4",
    "--embed-metadata",
    "--embed-chapters",
    "--convert-thumbnails", "jpg",
    "--write-info-json",
    "--write-thumbnail",
    "--merge-output-format", "mp4",
]


def format_selector(max_res: int) -> str:
    h = max_res or 1080
    return (
        f"bv*[height<={h}][vcodec*=avc1]+ba[acodec*=mp4a]/"
        f"bv*[height<={h}]+ba/"
        f"b[height<={h}]/b"
    )


def backfill_dateafter() -> str | None:
    """yt-dlp date window matching the configured backfill (None = off).

    yt-dlp's relative date grammar is "now-Ndays" (today/yesterday/now
    minus N day|week|month|year units) — "daysago" is NOT valid and
    aborts every download.
    """
    days = max(0, settings.backfill_days or 0)
    return f"now-{days}days" if days > 0 else None


def build_download_cmd(video_url: str, out_tmpl: str, archive: Path,
                       dateafter: str | None = None) -> list[str]:
    cmd = [
        "yt-dlp",
        *BASE_FLAGS,
        "--sponsorblock-api", settings.sponsorblock_api,
    ]
    # Empty mark/remove lists mean "do nothing" — omit the flags entirely
    # (passing an empty string would fail the download).
    if settings.sponsorblock_mark:
        cmd += ["--sponsorblock-mark", settings.sponsorblock_mark,
                "--sponsorblock-chapter-title", "[SponsorBlock]: %(category_names)l"]
    if settings.sponsorblock_remove:
        cmd += ["--sponsorblock-remove", settings.sponsorblock_remove]
    if dateafter:
        # Authoritative enforcement of the backfill window using yt-dlp's own
        # upload-date metadata — belt and braces for the pre-filter in the
        # poller (which works off RSS/batched dates).
        cmd += ["--dateafter", dateafter]
    return cmd + [
        "-f", format_selector(settings.max_resolution),
        "--download-archive", str(archive),
        "-o", out_tmpl,
        video_url,
    ]


def discover_channel_videos(playlist_url: str, limit: int = 10) -> list[dict]:
    """Flat scan of a channel's latest uploads without downloading.

    Takes a full uploads-playlist URL (e.g. .../channel/UC.../videos).
    Flat entries only carry id/title reliably — full metadata (date,
    duration) is backfilled from the .info.json after download.
    """
    cmd = [
        "yt-dlp", "--flat-playlist", "--no-warnings",
        "--playlist-end", str(limit),
        "--print", "%(.{id,title,channel,uploader})j",
        playlist_url,
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except Exception:
        return []
    out: list[dict] = []
    for line in (proc.stdout or "").splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            out.append(json.loads(line))
        except Exception:
            continue
    return out


def resolve_channel_id(channel_url: str) -> tuple[str | None, str | None]:
    """Return (channel_id, title) via a cheap flat lookup."""
    cmd = ["yt-dlp", "--flat-playlist", "--no-warnings", "--playlist-end", "1",
           "--print", "%(.{channel_id,channel,uploader})j", channel_url]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        for line in (proc.stdout or "").splitlines():
            if line.strip().startswith("{"):
                d = json.loads(line)
                return d.get("channel_id"), d.get("channel") or d.get("uploader")
    except Exception:
        pass
    return None, None


def download_video(video_id: str, media_dir: Path, archive: Path,
                   log: Callable[[str], None] | None = None,
                   dateafter: str | None = None) -> tuple[bool, str]:
    out_tmpl = str(media_dir / "%(channel)s/%(upload_date)s - %(title)s [%(id)s].%(ext)s")
    url = f"https://www.youtube.com/watch?v={video_id}"
    cmd = build_download_cmd(url, out_tmpl, archive, dateafter=dateafter)
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
        if log:
            tail = (proc.stderr or proc.stdout or "")[-2000:]
            log(f"yt-dlp {video_id} rc={proc.returncode} {tail}")
        return proc.returncode == 0, (proc.stderr or "")[-2000:]
    except subprocess.TimeoutExpired:
        return False, "timeout after 1h"
    except Exception as e:  # yt-dlp missing etc.
        return False, str(e)


def cutoff_ymd(days: int) -> str:
    """YYYYMMDD string for N days ago (lexicographically comparable)."""
    import datetime
    return (datetime.date.today() - datetime.timedelta(days=max(0, days))).strftime("%Y%m%d")


def fetch_rss_recent(channel_id: str) -> dict[str, str]:
    """Fast free lookup: the channel RSS feed's newest videos as {id: YYYYMMDD}.

    Flat playlist scans carry no dates, so this covers the common backfill case
    without per-video extraction. Times out fast and fails soft ({}).
    """
    import urllib.request
    import xml.etree.ElementTree as ET
    url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "cathode/0.1"})
        with urllib.request.urlopen(req, timeout=20) as r:
            root = ET.fromstring(r.read())
    except Exception:
        return {}
    ns = "{http://www.w3.org/2005/Atom}"
    yt = "{http://www.youtube.com/xml/schemas/2015}"
    out: dict[str, str] = {}
    try:
        for entry in root.findall(f"{ns}entry"):
            vid_el = entry.find(f"{yt}videoId")
            pub_el = entry.find(f"{ns}published")
            if vid_el is None or pub_el is None or not vid_el.text or not pub_el.text:
                continue
            ymd = pub_el.text[:10].replace("-", "")
            if len(ymd) == 8 and ymd.isdigit():
                out[vid_el.text.strip()] = ymd
    except Exception:
        return {}
    return out


def resolve_upload_dates(video_ids: list[str], limit: int = 150) -> dict[str, str]:
    """Batch-resolve upload dates (one yt-dlp process for many videos).

    Returns {id: YYYYMMDD}; ids that can't be dated are simply absent and the
    caller decides (backfill keeps them — safer than skipping unseen videos).
    """
    ids = [i for i in video_ids if i][:max(1, limit)]
    if not ids:
        return {}
    cmd = ["yt-dlp", "--no-warnings", "--skip-download",
           "--print", "%(id)s %(upload_date)s",
           *[f"https://www.youtube.com/watch?v={i}" for i in ids]]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    except Exception:
        return {}
    out: dict[str, str] = {}
    for line in (proc.stdout or "").splitlines():
        parts = line.strip().split()
        if len(parts) == 2 and len(parts[1]) == 8 and parts[1].isdigit():
            out[parts[0]] = parts[1]
    return out
