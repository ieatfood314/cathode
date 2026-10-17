"""Smoke tests for subscription parsing + API (no network, no personal data)."""

import re
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


def test_download_flags_exist_in_ytdlp():
    """Every --flag we pass must exist in the installed yt-dlp.

    A renamed/removed flag fails EVERY download (see: --continuedl), so this
    guards the exact binary the container ships.
    """
    import shutil
    import subprocess
    from app.downloader import BASE_FLAGS, build_download_cmd
    from pathlib import Path
    exe = shutil.which("yt-dlp")
    if exe is None:
        return  # dev machine without yt-dlp: CI image always has it
    help_text = subprocess.run([exe, "--help"], capture_output=True, text=True,
                               timeout=60).stdout
    assert "--continue" in help_text
    cmd = " ".join(build_download_cmd("https://www.youtube.com/watch?v=x", "/tmp/o", Path("/tmp/a")))
    for flag in ["--continue", "--retries", "--fragment-retries", "--retry-sleep",
                 "--socket-timeout", "--embed-metadata", "--embed-chapters",
                 "--write-info-json", "--write-thumbnail",
                 "--merge-output-format", "--download-archive", "--sponsorblock-mark",
                 "--sponsorblock-remove", "--sponsorblock-api"]:
        assert flag in cmd, flag
        assert re.search(rf"(^|\s){re.escape(flag)}(\s|,)", help_text), flag


def test_no_embedded_thumbnail():
    """Cover art breaks phone playback: it is added as a second video stream
    (mjpeg), which ExoPlayer picks as the video track — audio only, no picture.
    """
    import shutil as _sh
    from app.downloader import BASE_FLAGS, build_download_cmd
    from pathlib import Path
    assert "--embed-thumbnail" not in BASE_FLAGS
    assert "--embed-thumbnail" not in " ".join(
        build_download_cmd("https://www.youtube.com/watch?v=x", "/tmp/o", Path("/tmp/a")))
    # sanity: the guard test still validates flags only when yt-dlp exists
    assert _sh.which("yt-dlp") is None or "--embed-thumbnail" not in BASE_FLAGS


def test_backfill_strict_beyond_grace(monkeypatch):
    """Undatable videos past the newest few must NOT download (storage kill)."""
    import app.downloader as dl
    from app.poller import Poller
    monkeypatch.setattr(dl, "fetch_rss_recent", lambda cid: {"new1": "20990101"})
    monkeypatch.setattr(dl, "resolve_upload_dates",
                        lambda ids: {"old1": "20000101"} if ids else {})
    p = Poller()
    vids = [{"id": f"mystery{i}"} for i in range(20)] + [{"id": "old1"}, {"id": "new1"}]
    kept = [v["id"] for v in p._apply_backfill("UCchan", vids, 30)]
    assert all(f"mystery{i}" in kept for i in range(10))   # within grace window
    assert all(f"mystery{i}" not in kept for i in range(11, 20))  # beyond it
    assert "old1" not in kept and "new1" in kept


def test_progress_roundtrip():
    """Save playback position; auto-mark watched near the end."""
    from fastapi.testclient import TestClient
    from app import db as dbmod
    from app.config import settings
    from app.main import app
    conn = dbmod.connect(settings.db_path)
    conn.execute("INSERT OR REPLACE INTO videos(id,status,file_path,duration,watched) "
                 "VALUES('prog1','done','x.mp4',600,0)")
    conn.execute("INSERT OR REPLACE INTO videos(id,status,file_path,duration,watched) "
                 "VALUES('prog2','done','y.mp4',600,0)")
    conn.commit()
    conn.close()
    c = TestClient(app)
    r = c.post("/api/videos/prog1/progress", json={"position_seconds": 123})
    assert r.status_code == 200 and r.json()["watched"] is False
    conn = dbmod.connect(settings.db_path)
    pos = conn.execute("SELECT position_seconds FROM videos WHERE id='prog1'").fetchone()["position_seconds"]
    conn.close()
    assert pos == 123
    rows = [v for v in c.get("/api/feed?limit=500").json()["videos"] if v["id"] == "prog1"]
    assert rows and rows[0]["position_seconds"] == 123
    # Near the end (>=95%): auto-watched.
    r = c.post("/api/videos/prog2/progress", json={"position_seconds": 595})
    assert r.json()["watched"] is True
    conn = dbmod.connect(settings.db_path)
    w = conn.execute("SELECT watched FROM videos WHERE id='prog2'").fetchone()["watched"]
    conn.close()
    assert w == 1


def test_prune_beyond_backfill(tmp_path, monkeypatch):
    """One-time cleanup: downloads older than the backfill window are deleted."""
    from app import db as dbmod
    from app.config import settings
    from app.poller import Poller
    media = tmp_path / "media"
    chan = media / "Chan"
    chan.mkdir(parents=True)
    (chan / "20110101 - Ancient [anc123XYZ_-].mp4").write_bytes(b"v" * 100)
    (chan / "20990101 - Fresh [fresh123XYZ-].mp4").write_bytes(b"v" * 100)
    conn = dbmod.connect(str(tmp_path / "t.db"))
    conn.execute("INSERT INTO videos(id,status,file_path,upload_date) "
                 "VALUES('anc123XYZ_-','done','Chan/20110101 - Ancient [anc123XYZ_-].mp4','20110101')")
    conn.execute("INSERT INTO videos(id,status,file_path,upload_date) "
                 "VALUES('fresh123XYZ-','done','Chan/20990101 - Fresh [fresh123XYZ-].mp4','20990101')")
    conn.commit()
    monkeypatch.setattr(settings, "backfill_days", 5)
    monkeypatch.setattr(settings, "media_dir", str(media))
    fixed = Poller()._prune_beyond_backfill(conn, media)
    conn.close()
    assert fixed == 1
    assert not chan.exists() or not list(chan.glob("*anc*"))
    assert (chan / "20990101 - Fresh [fresh123XYZ-].mp4").exists()


def test_strip_cover_streams(tmp_path, monkeypatch):
    """Embedded cover art (second video stream) gets remuxed away.

    Needs ffmpeg; skipped on machines without it (the container ships it).
    """
    import shutil
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        return
    from app import db as dbmod
    from app.config import settings
    from app.poller import Poller
    import subprocess
    media = tmp_path / "media"
    media.mkdir(parents=True)
    src = media / "cover1.mp4"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error",
         "-f", "lavfi", "-i", "testsrc=size=160x120:rate=2",
         "-f", "lavfi", "-i", "color=c=black:size=160x120:rate=2",
         "-f", "lavfi", "-i", "sine=frequency=440",
         "-map", "0:v", "-map", "1:v", "-map", "2:a",
         "-c:v:0", "libx264", "-c:v:1", "mjpeg", "-c:a", "aac", "-t", "1",
         str(src)], check=True, capture_output=True)
    initial = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v",
         "-show_entries", "stream=codec_name", "-of", "csv=p=0", str(src)],
        check=True, capture_output=True, text=True).stdout.strip()
    assert "h264" in initial and "mjpeg" in initial
    conn = dbmod.connect(str(tmp_path / "t.db"))
    conn.execute("INSERT OR REPLACE INTO videos(id,status,file_path) "
                 "VALUES('cover1','done','cover1.mp4')")
    conn.commit()
    monkeypatch.setattr(settings, "media_dir", str(media))
    fixed = Poller()._strip_cover_streams(conn, media)
    conn.close()
    assert fixed == 1
    codecs = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v",
         "-show_entries", "stream=codec_name", "-of", "csv=p=0", str(src)],
        check=True, capture_output=True, text=True).stdout.strip()
    assert "h264" in codecs and "mjpeg" not in codecs


def test_dateafter_flag_with_backfill(monkeypatch):
    from pathlib import Path
    from app import config
    from app.downloader import backfill_dateafter, build_download_cmd
    monkeypatch.setattr(config.settings, "backfill_days", 5)
    da = backfill_dateafter()
    assert da == "now-5daysago"
    assert da in build_download_cmd("https://www.youtube.com/watch?v=x", "/tmp/o", Path("/tmp/a"), dateafter=da)
    monkeypatch.setattr(config.settings, "backfill_days", 0)
    assert backfill_dateafter() is None
    cmd = build_download_cmd("https://www.youtube.com/watch?v=x", "/tmp/o", Path("/tmp/a"))
    assert "--dateafter" not in cmd


def test_repair_missing_paths(tmp_path, monkeypatch):
    """Regression: yt-dlp names files '[id].ext' — the matcher must find them.

    (The old *id.* glob never matched, so downloads were marked done with
    empty paths and skipped forever.)
    """
    from app import db as dbmod
    from app.poller import Poller
    media = tmp_path / "media"
    chan = media / "SomeChannel"
    chan.mkdir(parents=True)
    (chan / "20240101 - Title One [abc123XYZ_-].mp4").write_bytes(b"v" * 100)
    (chan / "20240101 - Title One [abc123XYZ_-].jpg").write_bytes(b"t" * 10)
    (chan / "20240101 - Title One [abc123XYZ_-].info.json").write_text(
        '{"title": "Title One", "upload_date": "20240101", "duration": 60, '
        '"timestamp": 1704067200, "description": "hi"}')
    (chan / "unrelated.mp4").write_bytes(b"z")
    conn = dbmod.connect(str(tmp_path / "t.db"))
    conn.execute("INSERT INTO videos(id,status,file_path) VALUES('abc123XYZ_-','done','')")
    conn.commit()
    fixed = Poller()._repair_missing_paths(conn, media)
    assert fixed == 1
    row = conn.execute("SELECT file_path, filesize, title, published, duration FROM videos "
                       "WHERE id='abc123XYZ_-'").fetchone()
    assert row["file_path"].endswith(".mp4") and "[abc123XYZ_-]" in row["file_path"]
    assert row["filesize"] == 100
    assert row["title"] == "Title One" and row["published"] == 1704067200
    conn.close()
