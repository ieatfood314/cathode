"""Supported subscription inputs (all free, no YouTube Premium needed).

1. subscriptions.opml — Export from Google Takeout:
   Takeout > YouTube > Subscriptions > subscriptions.csv, or YouTube
   subscription-manager OPML export. We accept OPML <outline xmlUrl="...">
   entries where xmlUrl is the channel RSS feed, plus CSV with a
   "Channel Url" column (Takeout format).

2. channels.txt — one entry per line, any of:
    - https://www.youtube.com/@handle (also m.youtube.com, scheme-less, /c/, /user/)
    - https://www.youtube.com/channel/UCxxxx
    - UCxxxx (raw channel id)
    - @handle (or the bare handle)
    - video / shorts links — resolved to their channel automatically
    Lines starting with # are ignored.
"""

import csv
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit


@dataclass
class Subscription:
    source_url: str  # canonical channel or RSS url as given
    channel_id: str | None = None  # UC... when known (from RSS xmlUrl)
    title: str | None = None
    origin: str = ""  # opml | takeout | manual (which input file it came from)


RSS_RE = re.compile(r"channel_id=([A-Za-z0-9_-]{10,})")
CHANNEL_ID_RE = re.compile(r"^(UC[A-Za-z0-9_-]{20,})$")
CHANNEL_URL_RE = re.compile(r"/channel/(UC[A-Za-z0-9_-]{10,})")


def _rss_to_channel_id(xml_url: str) -> str | None:
    m = RSS_RE.search(xml_url or "")
    return m.group(1) if m else None


def parse_opml(path: Path) -> list[Subscription]:
    subs: list[Subscription] = []
    try:
        tree = ET.parse(path)
    except Exception:
        return []
    for outline in tree.iter("outline"):
        xml_url = (outline.get("xmlUrl") or "").strip()
        title = outline.get("title") or outline.get("text")
        if not xml_url:
            continue
        subs.append(
            Subscription(
                source_url=xml_url,
                channel_id=_rss_to_channel_id(xml_url),
                title=title,
            )
        )
    return subs


def parse_takeout_csv(path: Path) -> list[Subscription]:
    """Google Takeout subscriptions.csv: headers include Channel Id, Channel Url, Channel Title."""
    subs: list[Subscription] = []
    try:
        with path.open(newline="", encoding="utf-8-sig") as f:
            reader = csv.DictReader(f)
            if not reader.fieldnames:
                return []
            # normalise headers
            lower = {h.lower(): h for h in reader.fieldnames}
            url_key = lower.get("channel url")
            id_key = lower.get("channel id")
            title_key = lower.get("channel title")
            for row in reader:
                url = (row.get(url_key) or "").strip() if url_key else ""
                cid = (row.get(id_key) or "").strip() if id_key else ""
                title = (row.get(title_key) or "").strip() if title_key else None
                if not url and not cid:
                    continue
                subs.append(Subscription(source_url=url or cid, channel_id=cid or None, title=title))
    except Exception:
        return []
    return subs


def parse_channels_txt(path: Path) -> list[Subscription]:
    subs: list[Subscription] = []
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        return []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        cid = None
        m = CHANNEL_ID_RE.match(line)
        if m:
            cid = m.group(1)
        elif "youtube.com/feeds/videos.xml" in line:
            cid = _rss_to_channel_id(line)
        else:
            mu = CHANNEL_URL_RE.search(line)
            if mu:
                cid = mu.group(1)
        subs.append(Subscription(source_url=line, channel_id=cid))
    return subs


def load_subscriptions(data_dir: Path) -> list[Subscription]:
    """Merge all supported inputs, de-duplicated by (channel_id or source_url)."""
    seen: dict[str, Subscription] = {}
    for path, origin in [(data_dir / "subscriptions.opml", "opml"),
                         (data_dir / "subscriptions.xml", "opml"),
                         (data_dir / "subscriptions.csv", "takeout"),
                         (data_dir / "channels.txt", "manual")]:
        if path.suffix == ".csv":
            parsed = parse_takeout_csv(path)
        elif path.name == "channels.txt":
            parsed = parse_channels_txt(path)
        else:
            parsed = parse_opml(path)
        for s in parsed:
            s.origin = origin
            key = s.channel_id or s.source_url.strip().rstrip("/")
            if key and key not in seen:
                seen[key] = s
    return list(seen.values())


_VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,}$")
_CHANNEL_TAB_RE = re.compile(r"^(videos|shorts|streams|playlists|community|channels|about|featured)$")


def _default_resolve_video(video_id: str) -> str:
    """Turn a video id into its channel's canonical URL (one yt-dlp call)."""
    cmd = ["yt-dlp", "--no-warnings", "--print", "%(.{channel_id})j",
           f"https://www.youtube.com/watch?v={video_id}"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except Exception:
        raise ValueError("couldn't open that video — check the link and try again")
    for line in (proc.stdout or "").splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                cid = json.loads(line).get("channel_id")
            except Exception:
                continue
            if cid:
                return f"https://www.youtube.com/channel/{cid}"
    raise ValueError("couldn't tell which channel that video is from — paste the channel link instead")


def normalize_channel_input(raw: str, resolve_video=None) -> str:
    """Turn almost anything YouTube-ish into a canonical channel URL.

    Accepts: full/partial URLs, www/m/music subdomains, /channel/ /c/ /user/ /
    @handle paths (with trailing tabs like /videos), RSS feed URLs, UC ids,
    @handles, bare handles, and video/shorts/live links (resolved to their
    channel). Raises ValueError with a human-readable reason otherwise.
    """
    resolve = resolve_video or _default_resolve_video
    s = (raw or "").strip()
    if not s:
        raise ValueError("type or paste a channel link first")
    if re.fullmatch(r"UC[A-Za-z0-9_-]{21,}", s):
        return f"https://www.youtube.com/channel/{s}"
    m = re.fullmatch(r"@([A-Za-z0-9._-]{3,30})", s)
    if m:
        return f"https://www.youtube.com/@{m.group(1)}"
    if re.fullmatch(r"[A-Za-z0-9_-]{3,30}", s):
        return f"https://www.youtube.com/@{s}"
    if "://" not in s:
        if " " in s or "." not in s:
            raise ValueError("that doesn't look like a link — paste the full channel URL")
        s = "https://" + s
    try:
        u = urlsplit(s)
    except Exception:
        raise ValueError("that doesn't look like a link — paste the full channel URL")
    host = (u.hostname or "").lower()
    if host == "youtu.be":
        vid = u.path.strip("/").split("/")[0]
        if not _VIDEO_ID_RE.match(vid):
            raise ValueError("couldn't find a video in that short link")
        return resolve(vid)
    if host != "youtube.com" and not host.endswith(".youtube.com"):
        raise ValueError("that's not a YouTube link")
    segs = [p for p in u.path.rstrip("/").split("/") if p]
    qd = dict(parse_qsl(u.query))
    if segs[:1] == ["watch"] and _VIDEO_ID_RE.match(qd.get("v", "")):
        return resolve(qd["v"])
    if len(segs) >= 2 and segs[0] in ("shorts", "live", "embed") and _VIDEO_ID_RE.match(segs[1]):
        return resolve(segs[1])
    if segs[:1] == ["playlist"]:
        raise ValueError("playlists aren't tracked — add the channel itself instead")
    if segs[:1] == ["feeds"]:
        return s.split("?")[0] + ("?" + u.query if u.query else "")
    if len(segs) >= 2 and segs[0] in ("channel", "c", "user"):
        return f"https://www.youtube.com/{segs[0]}/{segs[1]}"
    if segs and segs[0].startswith("@"):
        handle = segs[0].split("?")[0]
        return f"https://www.youtube.com/{handle}"
    raise ValueError("couldn't find a channel in that link — open the channel page and copy its URL")
