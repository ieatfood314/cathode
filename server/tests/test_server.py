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


def test_add_remove_channel(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    from app.config import settings
    from app.main import app
    monkeypatch.setattr(settings, "data_dir", str(tmp_path))
    c = TestClient(app)
    r = c.post("/api/subscriptions", json={"url": "https://www.youtube.com/@example"})
    assert r.status_code == 200 and r.json()["added"] is True
    r = c.post("/api/subscriptions", json={"url": "https://www.youtube.com/@example"})
    assert r.json()["added"] is False
    assert c.post("/api/subscriptions", json={"url": "not a url"}).status_code == 400
    r = c.delete("/api/subscriptions", params={"url": "https://www.youtube.com/@example"})
    assert r.json()["removed"] is True
    assert c.delete("/api/subscriptions", params={"url": "https://www.youtube.com/@example"}).status_code == 404


def test_upload_subscriptions(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    from app.config import settings
    from app.main import app
    monkeypatch.setattr(settings, "data_dir", str(tmp_path))
    c = TestClient(app)
    opml = ('<opml><body><outline text="C" xmlUrl="https://www.youtube.com/feeds/videos.xml'
            '?channel_id=UC1234567890123456789012"/></body></opml>')
    r = c.post("/api/subscriptions/upload", files={"file": ("subs.opml", opml)})
    assert r.status_code == 200 and r.json()["subscriptions"] == 1
    r = c.post("/api/subscriptions/upload", files={"file": ("x.exe", "junk")})
    assert r.status_code == 400
    r = c.post("/api/subscriptions/upload",
               files={"file": ("list.txt", "https://www.youtube.com/@a\nhttps://www.youtube.com/@a\n")})
    assert r.json()["subscriptions"] == 1  # de-duplicated
    subs = c.get("/api/subscriptions").json()["inputs"]
    assert any(s["origin"] == "manual" for s in subs)


def test_sponsorblock_sanitized(monkeypatch):
    from fastapi.testclient import TestClient
    from app.config import settings
    from app.main import app
    monkeypatch.setattr(settings, "sponsorblock_remove", "sponsor")
    c = TestClient(app)
    r = c.put("/api/settings", json={"sponsorblock_remove": "sponsor, nonsense, SELFpromo"})
    assert r.json()["settings"]["sponsorblock_remove"] == "sponsor,selfpromo"


def test_watched_deletes_server_file(monkeypatch):
    from pathlib import Path as _P
    from fastapi.testclient import TestClient
    from app import db as dbmod
    from app.config import settings
    from app.main import app
    media = _P(settings.media_dir)
    media.mkdir(parents=True, exist_ok=True)
    (media / "gone [abc123].mp4").write_bytes(b"data")
    conn = dbmod.connect(settings.db_path)
    conn.execute("INSERT OR REPLACE INTO videos(id,status,file_path,watched) "
                 "VALUES('abc123','done','gone [abc123].mp4',0)")
    conn.commit()
    conn.close()
    monkeypatch.setattr(settings, "delete_after_watched", True)
    c = TestClient(app)
    r = c.post("/api/videos/abc123/watched?watched=true")
    assert r.json()["deleted_server_file"] is True
    assert not (media / "gone [abc123].mp4").exists()
    conn = dbmod.connect(settings.db_path)
    row = conn.execute("SELECT status FROM videos WHERE id='abc123'").fetchone()
    conn.close()
    assert row["status"] == "deleted"
    assert c.get("/api/feed").json()["videos"] == [] or all(
        v["id"] != "abc123" for v in c.get("/api/feed").json()["videos"])


def test_normalize_channel_input():
    from app.subscriptions import normalize_channel_input as n
    fake = lambda vid: "https://www.youtube.com/channel/UCresolved"
    assert n("UC1234567890123456789012") == "https://www.youtube.com/channel/UC1234567890123456789012"
    assert n("@t3dotgg") == "https://www.youtube.com/@t3dotgg"
    assert n("t3dotgg") == "https://www.youtube.com/@t3dotgg"
    assert n("https://www.youtube.com/@t3dotgg/videos") == "https://www.youtube.com/@t3dotgg"
    assert n("https://m.youtube.com/@t3dotgg") == "https://www.youtube.com/@t3dotgg"
    assert n("m.youtube.com/@t3dotgg") == "https://www.youtube.com/@t3dotgg"
    assert n("youtube.com/c/SomeName?si=abc") == "https://www.youtube.com/c/SomeName"
    assert n("https://www.youtube.com/user/OldUser/about") == "https://www.youtube.com/user/OldUser"
    assert n("https://www.youtube.com/channel/UCabc/videos") == "https://www.youtube.com/channel/UCabc"
    assert n("https://youtu.be/dQw4w9WgXcQ", resolve_video=fake) == "https://www.youtube.com/channel/UCresolved"
    assert n("https://www.youtube.com/watch?v=dQw4w9WgXcQ", resolve_video=fake) == \
        "https://www.youtube.com/channel/UCresolved"
    assert n("https://www.youtube.com/shorts/dQw4w9WgXcQ", resolve_video=fake) == \
        "https://www.youtube.com/channel/UCresolved"
    for bad in ["", "not a url", "https://example.com/@x", "https://www.youtube.com/playlist?list=PL1",
                "https://www.youtube.com/results?search_query=x"]:
        try:
            n(bad, resolve_video=fake)
        except ValueError:
            continue
        raise AssertionError(f"should have rejected: {bad}")


def test_add_normalizes_and_rejects(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    from app.config import settings
    from app.main import app
    monkeypatch.setattr(settings, "data_dir", str(tmp_path))
    c = TestClient(app)
    r = c.post("/api/subscriptions", json={"url": "https://m.youtube.com/@example/videos"})
    assert r.status_code == 200 and r.json()["url"] == "https://www.youtube.com/@example"
    r = c.post("/api/subscriptions", json={"url": "@example"})
    assert r.json()["added"] is False  # same channel, different form
    r = c.post("/api/subscriptions", json={"url": "https://vimeo.com/123"})
    assert r.status_code == 400


def test_backfill_filter(monkeypatch):
    import app.downloader as dl
    from app.poller import Poller
    monkeypatch.setattr(dl, "fetch_rss_recent", lambda cid: {"new1": "20990101"})
    monkeypatch.setattr(dl, "resolve_upload_dates",
                        lambda ids: {"old1": "20000101"} if ids else {})
    p = Poller()
    vids = [{"id": "new1"}, {"id": "old1"}, {"id": "mystery1"}, {"id": "UCnotaVideo"}]
    kept = p._apply_backfill("UCchan", vids, 30)
    ids = [v["id"] for v in kept]
    assert "new1" in ids and "mystery1" in ids  # in-window + undatable kept
    assert "old1" not in ids and "UCnotaVideo" not in ids


def test_cutoff_ymd_format():
    from app.downloader import cutoff_ymd
    import re
    assert re.fullmatch(r"\d{8}", cutoff_ymd(30))
    assert cutoff_ymd(30) < cutoff_ymd(5) <= cutoff_ymd(0)
