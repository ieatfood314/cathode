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
  position_seconds REAL DEFAULT 0,
  FOREIGN KEY(channel_id) REFERENCES channels(id)
);
CREATE TABLE IF NOT EXISTS kv (
  k TEXT PRIMARY KEY,
  v TEXT
);
CREATE INDEX IF NOT EXISTS idx_videos_published ON videos(published DESC);
"""


def adopt_legacy_db(db_path: str) -> None:
    """Pick up a database left by the project's old name (ruminate.db) when
    the configured path doesn't exist yet."""
    target = Path(db_path)
    legacy = target.with_name("ruminate.db")
    if target.exists() or target == legacy or not legacy.exists():
        return
    for suffix in ("", "-wal", "-shm", "-journal"):
        src = Path(str(legacy) + suffix)
        if src.exists():
            src.rename(str(target) + suffix)


def connect(db_path: str) -> sqlite3.Connection:
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    _migrate(conn)
    return conn


def _migrate(conn: sqlite3.Connection):
    """Light ALTERs for databases created before a column existed."""
    cols = {r["name"] for r in conn.execute("PRAGMA table_info(videos)")}
    if "position_seconds" not in cols:
        conn.execute("ALTER TABLE videos ADD COLUMN position_seconds REAL DEFAULT 0")
        conn.commit()


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


# Server settings persist across container restarts (a restart loses
# process memory otherwise — see the 0.1.6 restart that reset keep/backfill).
PERSISTED_SETTINGS = [
    "poll_mode", "poll_interval_minutes", "max_resolution",
    "sponsorblock_remove", "sponsorblock_mark", "keep_days",
    "backfill_days", "delete_after_watched",
]


def save_settings_snapshot(settings_obj) -> None:
    import json
    try:
        conn = sqlite3.connect(str(settings_obj.db_path))
        conn.execute("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)")
        conn.execute(
            "INSERT INTO kv(k, v) VALUES('server_settings', ?) "
            "ON CONFLICT(k) DO UPDATE SET v=excluded.v",
            (json.dumps({k: getattr(settings_obj, k) for k in PERSISTED_SETTINGS}),),
        )
        conn.commit()
        conn.close()
    except Exception:
        pass


def restore_settings_snapshot(settings_obj) -> None:
    import json
    if not Path(settings_obj.db_path).exists():
        return
    try:
        conn = sqlite3.connect(str(settings_obj.db_path))
        conn.row_factory = sqlite3.Row
        try:
            row = conn.execute("SELECT v FROM kv WHERE k='server_settings'").fetchone()
        except Exception:
            row = None
        conn.close()
        if not row or not row["v"]:
            return
        saved = json.loads(row["v"])
    except Exception:
        return
    # The snapshot is what was last saved from the app, so it wins over env:
    # docker-compose passes every CATHODE_* setting with a default, and
    # treating those as explicit would reset the app's choices on every
    # restart. Env only seeds a fresh install.
    for k in PERSISTED_SETTINGS:
        if k in saved:
            setattr(settings_obj, k, saved[k])
