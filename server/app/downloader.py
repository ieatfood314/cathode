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
"""

import json
import subprocess
from collections.abc import Callable
from pathlib import Path

from .config import settings

BASE_FLAGS = [
    "--no-progress",
    "--continuedl",
    "--retries", "10",
    "--fragment-retries", "10",
    "--file-access-retries", "10",
    "--retry-sleep", "fragment:exp=1:10",
    "--socket-timeout", "30",
    "-N", "4",
    "--concurrent-fragments", "4",
    "--embed-metadata",
    "--embed-chapters",
    "--embed-thumbnail",
    "--convert-thumbnails", "jpg",
    "--writeinfojson",
    "--writethumbnail",
    "--merge-output-format", "mp4",
]


def format_selector(max_res: int) -> str:
    h = max_res or 1080
    return (
        f"bv*[height<={h}][vcodec*=avc1]+ba[acodec*=mp4a]/"
        f"bv*[height<={h}]+ba/"
        f"b[height<={h}]/b"
    )


def build_download_cmd(video_url: str, out_tmpl: str, archive: Path) -> list[str]:
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
                   log: Callable[[str], None] | None = None) -> tuple[bool, str]:
    out_tmpl = str(media_dir / "%(channel)s/%(upload_date)s - %(title)s [%(id)s].%(ext)s")
    url = f"https://www.youtube.com/watch?v={video_id}"
    cmd = build_download_cmd(url, out_tmpl, archive)
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
