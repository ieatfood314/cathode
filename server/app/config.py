"""Central configuration. Everything is env-overridable so no personal info is hardcoded.

Generic self-host: point RUMINATE_DATA_DIR at a folder containing
subscriptions.opml (YouTube Takeout export, free) and/or channels.txt.
"""

import os

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    data_dir: str = "/data"
    media_dir: str = "/media"
    db_path: str = "/data/ruminate.db"
    archive_path: str = "/data/archive.txt"

    # Polling: how the server discovers new uploads.
    # - periodic: background loop every poll_interval_minutes
    # - instant: tight loop (poll_interval_minutes clamped to >=5) for near-instant grabs
    # - on_open: only polls when the app calls POST /api/subscriptions/refresh
    #   (app also triggers this on every cold open; see android SyncWorker)
    poll_mode: str = "periodic"
    poll_interval_minutes: int = 30

    # yt-dlp quality + SponsorBlock defaults.
    # Removing only unambiguous ad segments; marking the rest as chapters
    # keeps archive jobs fast (remove forces a re-encode).
    max_resolution: int = 1080
    sponsorblock_remove: str = "sponsor,selfpromo,interaction"
    sponsorblock_mark: str = "all"
    sponsorblock_api: str = "https://sponsor.ajay.app"

    # Retention: 0 = keep forever.
    keep_days: int = 0
    max_videos_per_channel_scan: int = 10

    # HTTP server
    host: str = "0.0.0.0"
    port: int = 8000

    class Config:
        env_prefix = "RUMINATE_"


settings = Settings()

# Backwards-compat for the short names used in early docs/scripts:
# RUMINATE_DATA -> RUMINATE_DATA_DIR, RUMINATE_MEDIA -> RUMINATE_MEDIA_DIR.
if os.getenv("RUMINATE_DATA") and not os.getenv("RUMINATE_DATA_DIR"):
    settings.data_dir = os.environ["RUMINATE_DATA"]
if os.getenv("RUMINATE_MEDIA") and not os.getenv("RUMINATE_MEDIA_DIR"):
    settings.media_dir = os.environ["RUMINATE_MEDIA"]
