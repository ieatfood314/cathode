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


def _seed_video(c, file_path: str = "fake.mp4") -> str:
    from app import db as dbmod
    from app.config import settings
    conn = dbmod.connect(settings.db_path)
    conn.execute(
        "INSERT OR REPLACE INTO videos(id,status,file_path,published) "
        "VALUES('vid-test','done',?,1)",
        (file_path,),
    )
    conn.commit()
    conn.close()
    return "vid-test"


def test_stream_rejects_absolute_path():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    _seed_video(c, "/etc/passwd")
    r = c.get("/api/videos/vid-test/stream")
    assert r.status_code == 403


def test_stream_rejects_inverted_range():
    from pathlib import Path as _P
    from fastapi.testclient import TestClient
    from app.config import settings
    from app.main import app
    media = _P(settings.media_dir)
    media.mkdir(parents=True, exist_ok=True)
    (media / "range.mp4").write_bytes(b"x" * 1000)
    c = TestClient(app)
    _seed_video(c, "range.mp4")
    assert c.get("/api/videos/vid-test/stream",
                 headers={"Range": "bytes=500-100"}).status_code == 416
    r = c.get("/api/videos/vid-test/stream", headers={"Range": "bytes=10-19"})
    assert r.status_code == 206 and len(r.content) == 10


def test_refresh_returns_immediately():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    r = c.post("/api/subscriptions/refresh")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True and "started" in body
