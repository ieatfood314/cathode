"""SQLite index of channels + videos. Plain sqlite3, no ORM."""

import sqlite3
import time
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS channels (
  id TEXT PRIMARY KEY,
  url TEXT NOT NULL,
  title TEXT,
  last_checked INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS videos (
  id TEXT PRIMARY KEY,
  channel_id TEXT,
  channel_title TEXT,
  title TEXT,
  upload_date TEXT,
  duration INTEGER DEFAULT 0,
  description TEXT DEFAULT '',
  file_path TEXT,
  thumb_path TEXT,
  info_path TEXT,
  filesize INTEGER DEFAULT 0,
  status TEXT DEFAULT 'pending',
  error TEXT DEFAULT '',
  published INTEGER DEFAULT 0,
  downloaded_at INTEGER DEFAULT 0,
  watched INTEGER DEFAULT 0,
  FOREIGN KEY(channel_id) REFERENCES channels(id)
);
CREATE TABLE IF NOT EXISTS kv (
  k TEXT PRIMARY KEY,
  v TEXT
);
CREATE INDEX IF NOT EXISTS idx_videos_published ON videos(published DESC);
"""


def connect(db_path: str) -> sqlite3.Connection:
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    return conn


def upsert_channel(conn: sqlite3.Connection, channel_id: str, url: str, title: str | None = None):
    conn.execute(
        "INSERT INTO channels(id, url, title) VALUES(?,?,?) "
        "ON CONFLICT(id) DO UPDATE SET url=excluded.url, title=COALESCE(excluded.title, channels.title)",
        (channel_id, url, title),
    )
    conn.commit()


def upsert_video(conn: sqlite3.Connection, row: dict):
    cols = ["id", "channel_id", "channel_title", "title", "upload_date", "duration",
            "description", "published"]
    vals = [row.get(c) for c in cols]
    conn.execute(
        "INSERT INTO videos(id, channel_id, channel_title, title, upload_date, duration, description, published)"
        " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
        "channel_title=COALESCE(excluded.channel_title, videos.channel_title),"
        "title=COALESCE(excluded.title, videos.title)",
        vals,
    )
    conn.commit()


def mark_downloaded(conn: sqlite3.Connection, video_id: str, file_path: str,
                     thumb: str, info: str, filesize: int):
    conn.execute(
        "UPDATE videos SET status='done', file_path=?, thumb_path=?, info_path=?, "
        "filesize=?, downloaded_at=? WHERE id=?",
        (file_path, thumb, info, filesize, int(time.time()), video_id),
    )
    conn.commit()


def mark_failed(conn: sqlite3.Connection, video_id: str, error: str):
    conn.execute("UPDATE videos SET status='failed', error=? WHERE id=?", (error[:2000], video_id))
    conn.commit()


def now() -> int:
    return int(time.time())
