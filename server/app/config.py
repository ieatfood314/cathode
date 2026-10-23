"""Central configuration. Everything is env-overridable so no personal info is hardcoded.

Generic self-host: point CATHODE_DATA_DIR at a folder containing
subscriptions.opml (YouTube Takeout export, free) and/or channels.txt.
"""

import os

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CATHODE_")

    data_dir: str = "/data"
    media_dir: str = "/media"
    db_path: str = "/data/cathode.db"
    archive_path: str = "/data/archive.txt"

    # Polling: how the server discovers new uploads.
    # - periodic: background loop every poll_interval_minutes
    # - instant: every 5 minutes (poll_interval_minutes ignored) for near-instant grabs
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
    # Backfill: 0 = off (latest-N videos only).
    # N > 0 also grabs everything uploaded in the last N days per channel.
    backfill_days: int = 0
    # Delete the server file once you mark a video watched (phone copies are
    # managed separately in the app). Off by default — destructive on a shared library.
    delete_after_watched: bool = False
    max_videos_per_channel_scan: int = 10

    # HTTP server
    host: str = "0.0.0.0"
    port: int = 8000


# Backwards-compat: the project was called Ruminate, so existing .env files
# and compose setups still say RUMINATE_*. Those apply unless the CATHODE_*
# equivalent is set.
for _k, _v in list(os.environ.items()):
    if _k.startswith("RUMINATE_"):
        os.environ.setdefault("CATHODE_" + _k[len("RUMINATE_"):], _v)

settings = Settings()

# Backwards-compat for the short names used in early docs/scripts:
# CATHODE_DATA -> CATHODE_DATA_DIR, CATHODE_MEDIA -> CATHODE_MEDIA_DIR.
if os.getenv("CATHODE_DATA") and not os.getenv("CATHODE_DATA_DIR"):
    settings.data_dir = os.environ["CATHODE_DATA"]
if os.getenv("CATHODE_MEDIA") and not os.getenv("CATHODE_MEDIA_DIR"):
    settings.media_dir = os.environ["CATHODE_MEDIA"]
