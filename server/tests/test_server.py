"""Smoke tests for subscription parsing + API (no network, no personal data)."""

from pathlib import Path

from app.subscriptions import (
    load_subscriptions,
    parse_channels_txt,
    parse_opml,
    parse_takeout_csv,
)


def test_parse_opml(tmp_path: Path):
    p = tmp_path / "subs.opml"
    p.write_text("""<?xml version="1.0"?>
<opml><body>
<outline text="Some Channel" title="Some Channel"
  xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UC1234567890123456789012"/>
</body></opml>""")
    subs = parse_opml(p)
    assert len(subs) == 1
    assert subs[0].channel_id == "UC1234567890123456789012"


def test_parse_channels_txt(tmp_path: Path):
    p = tmp_path / "channels.txt"
    p.write_text("# comment\nhttps://www.youtube.com/@example\nUC1234567890123456789012\n")
    subs = parse_channels_txt(p)
    assert len(subs) == 2


def test_parse_takeout_csv(tmp_path: Path):
    p = tmp_path / "subscriptions.csv"
    p.write_text("Channel Id,Channel Url,Channel Title\n"
                 "UCabc,https://www.youtube.com/channel/UCabc,Example\n")
    subs = parse_takeout_csv(p)
    assert subs and subs[0].channel_id == "UCabc"


def test_load_dedupes(tmp_path: Path):
    (tmp_path / "subscriptions.opml").write_text(
        '<opml><body><outline xmlUrl="https://www.youtube.com/feeds/videos.xml'
        '?channel_id=UC1234567890123456789012"/></body></opml>')
    (tmp_path / "channels.txt").write_text(
        "https://www.youtube.com/channel/UC1234567890123456789012\n")
    subs = load_subscriptions(tmp_path)
    assert len(subs) == 1


def test_format_selector():
    from app.downloader import format_selector
    s = format_selector(1080)
    assert "1080" in s and "bv*" in s


def test_api_health():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    r = c.get("/api/health")
    assert r.status_code == 200
    assert r.json()["ok"] is True
