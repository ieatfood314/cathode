"""Supported subscription inputs (all free, no YouTube Premium needed).

1. subscriptions.opml — Export from Google Takeout:
   Takeout > YouTube > Subscriptions > subscriptions.csv, or YouTube
   subscription-manager OPML export. We accept OPML <outline xmlUrl="...">
   entries where xmlUrl is the channel RSS feed, plus CSV with a
   "Channel Url" column (Takeout format).

2. channels.txt — one entry per line, any of:
   - https://www.youtube.com/@handle
   - https://www.youtube.com/channel/UCxxxx
   - https://www.youtube.com/c/Name
   - https://www.youtube.com/@handle/videos
   - UCxxxx (raw channel id)
   - @handle
   Lines starting with # are ignored.
"""

import csv
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


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
